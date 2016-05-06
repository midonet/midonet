# Copyright 2016 Midokura SARL
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

from nose.plugins.attrib import attr
from mdts.lib.vtm_neutron import NeutronTopologyManager
from mdts.lib.bindings import BindingManager
from mdts.services import service
from mdts.tests.utils.asserts import *
from mdts.tests.utils.utils import bindings
from mdts.tests.utils.utils import wait_on_futures
from mdts.tests.utils.utils import get_midonet_api

from hamcrest import *
from nose.tools import with_setup
from nose.tools import nottest

import logging
import time
import pdb

LOG = logging.getLogger(__name__)

virtual_ip = '192.168.0.100'

# Two networks (one external, one internal)
# There is a vip port on the internal network, which is backed
# by two other ports. A floating ip is mapped onto the vip.
class VT_Networks_with_SG(NeutronTopologyManager):
    fip_ip = None

    def build(self, binding_data=None):
        global virtual_ip
        (public_net, public_subnet) = self.add_network(
            'public', '1.0.0.0/24', '1.0.0.1', external=True)
        (private_net, private_subnet) = self.add_network(
            'private', '192.168.0.0/24', '192.168.0.1')

        # disable antispoof protection on the private network
        midonet_api = get_midonet_api()
        bridge = midonet_api.get_bridge(private_net['network']['id'])
        bridge.disable_anti_spoof(True).update()

        ext0 = self.add_port('port_ext0', public_net['network']['id'])
        int0 = self.add_port('port_int0', private_net['network']['id'])
        int1 = self.add_port('port_int1', private_net['network']['id'],
                             vip=virtual_ip)
        int2 = self.add_port('port_int2', private_net['network']['id'],
                             vip=virtual_ip)
        vip0 = self.add_port('port_vip0', private_net['network']['id'],
                             subnet_id=private_subnet['subnet']['id'],
                             real_ip=virtual_ip)

        self.add_router('garprtr',
                        public_net['network']['id'],
                        private_subnet['subnet']['id'])

        try:
            self.create_resource(
                self.api.create_security_group_rule({
                    'security_group_rule': {
                        'direction': 'ingress',
                        'port_range_min': 0,
                        'port_range_max': 65535,
                        'protocol': 'udp',
                        'security_group_id': port2['port']['security_groups'][0]
                    }
                }))
        except Exception, e:
            LOG.debug('Error creating security group ' +
                      '(It could already exist)... continuing. %s' % e)

        fip = self.create_resource(
            self.api.create_floatingip({
                'floatingip': {
                    'floating_network_id': public_net['network']['id'],
                    'port_id': vip0['port']['id'],
                    'tenant_id': 'admin'
                }
            }))
        self.fip_ip = fip['floatingip']['floating_ip_address']

    def get_fip_ip(self):
        return self.fip_ip

    def add_network(self, name, cidr, gateway, external=False):
        network = self.create_resource(
            self.api.create_network({'network': {'name': name,
                                                 'admin_state_up': True,
                                                 'router:external': external,
                                                 'tenant_id': 'admin'}}))
        subnet = self.create_resource(
            self.api.create_subnet(
                {'subnet':
                    {'name': network['network']['name']+'_subnet',
                     'network_id': network['network']['id'],
                     'ip_version': 4,
                     'cidr': cidr,
                     'gateway_ip': gateway,
                     'enable_dhcp': True}}))
        return (network, subnet)

    def add_router(self, name, external_net, internal_subnet):
        router = self.create_resource(
            self.api.create_router(
                    {'router': {'name': name,
                                'tenant_id': 'admin',
                                'external_gateway_info': {
                                    'network_id': external_net
                                }}}))

        router_if = self.api.add_interface_router(
            router['router']['id'], {'subnet_id': internal_subnet})
        self.addCleanup(self.api.remove_interface_router,
                        router['router']['id'],
                        {'subnet_id': internal_subnet})
        return router

    def add_port(self, name, network_id, subnet_id=None, real_ip=None, vip=None):
        port_spec = {'name': name,
                     'network_id': network_id,
                     'admin_state_up': True,
                     'tenant_id': 'admin' }

        if real_ip != None and subnet_id != None:
            port_spec['fixed_ips'] = [{'ip_address': real_ip,
                                       'subnet_id': subnet_id }]
        # allowed address pair not yet supported in 1.9. Uncomment if supported
        # in future. Until then, we disable antispoof protection on the bridge.
        #if vip != None:
        #    port_spec['allowed_address_pairs'] = [ { 'ip_address' : vip } ]
        return self.create_resource(self.api.create_port({'port': port_spec}))

VTM = VT_Networks_with_SG()
BM = BindingManager(None, VTM)

binding_multinode = {
    'description': 'multinode node setup',
    'bindings': [
        {'vport': 'port_ext0',
         'interface': {
             'definition': { 'ipv4_gw': '1.0.0.1' },
             'hostname': 'midolman1',
             'type': 'vmguest'
         }},
        {'vport': 'port_int0',
         'interface': {
             'definition': { 'ipv4_gw': '192.168.0.1' },
             'hostname': 'midolman2',
             'type': 'vmguest'
         }},
        {'vport': 'port_int1',
         'interface': {
             'definition': { 'ipv4_gw': '192.168.0.1' },
             'hostname': 'midolman3',
             'type': 'vmguest'
         }},
        {'vport': 'port_int2',
         'interface': {
             'definition': { 'ipv4_gw': '192.168.0.1' },
             'hostname': 'midolman1',
             'type': 'vmguest'
         }}
    ]
}

def enable_vip(port, virtual_ip):
    port.execute('ip address add %s/32 dev %s' % (virtual_ip, port.get_ifname()),
                 sync=True)
    # Send both a request garp and a reply garp
    # NOTE: we only respect reply garp
    port.execute('arping -c 1 -A -I %s %s' % (port.get_ifname(), virtual_ip),
                 sync=True)
    port.execute('arping -c 1 -U -I %s %s' % (port.get_ifname(), virtual_ip),
                 sync=True)


def disable_vip(port, virtual_ip):
    port.execute('ip address del %s/32 dev %s' % (virtual_ip, port.get_ifname()),
                 sync=True)

def run_garp_scenario(sender_port, target_ip, virtual_ip):
    vip1 = BM.get_interface_on_vport('port_int1')
    vip2 = BM.get_interface_on_vport('port_int2')

    sender = BM.get_interface_on_vport(sender_port)
    # allow sender to accept gratutious arps (only makes sense if on same network)
    sender.execute('bash -c "echo 1 > /proc/sys/net/ipv4/conf/%s/arp_accept"'
                   % sender.get_ifname());
    rcv_filter = 'icmp and ip src %s' % (sender.get_ip())

    # noone responds initially
    f1 = async_assert_that(vip1, should_NOT_receive(rcv_filter, within_sec(10)))
    f2 = async_assert_that(vip2, should_NOT_receive(rcv_filter, within_sec(10)))
    f3 = sender.ping_ipv4_addr(target_ip, count=5)
    wait_on_futures([f1, f2, f3])

    # enable for vip1
    enable_vip(vip1, virtual_ip)
    disable_vip(vip2, virtual_ip)
    f1 = async_assert_that(vip1, receives(rcv_filter, within_sec(10)))
    f2 = async_assert_that(vip2, should_NOT_receive(rcv_filter, within_sec(10)))
    f3 = sender.ping_ipv4_addr(target_ip, count=5)
    wait_on_futures([f1, f2, f3])

    # enable for vip2
    enable_vip(vip2, virtual_ip)
    disable_vip(vip1, virtual_ip)
    f1 = async_assert_that(vip1, should_NOT_receive(rcv_filter, within_sec(10)))
    f2 = async_assert_that(vip2, receives(rcv_filter, within_sec(10)))
    f3 = sender.ping_ipv4_addr(target_ip, count=5)
    wait_on_futures([f1, f2, f3])

    # enable for vip1
    enable_vip(vip1, virtual_ip)
    disable_vip(vip2, virtual_ip)
    f1 = async_assert_that(vip1, receives(rcv_filter, within_sec(10)))
    f2 = async_assert_that(vip2, should_NOT_receive(rcv_filter, within_sec(10)))
    f3 = sender.ping_ipv4_addr(target_ip, count=5)
    wait_on_futures([f1, f2, f3])

@attr(version="v1.2.0")
@bindings(binding_multinode,
          binding_manager=BM)
def test_garp_over_bridge():
    """
    Title: Access a VIP using GARP from an endpoint on a local bridge
    """
    global virtual_ip
    run_garp_scenario('port_int0', virtual_ip, virtual_ip)


@attr(version="v1.2.0")
@bindings(binding_multinode,
          binding_manager=BM)
def test_garp_over_router():
    """
    Title: Access a VIP using GARP from an endpoint over a router

    Using ARP request type GARP.
    """
    global virtual_ip
    run_garp_scenario('port_ext0', VTM.get_fip_ip(), virtual_ip)




