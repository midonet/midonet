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

from hamcrest import *
from nose.tools import with_setup

import logging, pdb, re, time

LOG = logging.getLogger(__name__)


# Two private networks (net_1 & net_2) and a public network
# Each network has one vm. Both vms have a floating ip.
# The default security group allows all ingress udp traffic
# and all ingress icmp traffic.
class VT_Networks_with_SG(NeutronTopologyManager):
    fip_left = None
    fip_right = None

    router_left = None
    router_right = None

    def build(self, binding_data=None):
        (public, public_subnet) = self.add_network('public', '1.0.0.0/8', '1.0.0.1', True)
        (left, subnet_left) = self.add_network('left', '10.0.0.0/24', '10.0.0.1')
        (right, subnet_right) = self.add_network('right', '20.0.0.0/24', '20.0.0.1')
        port_left = self.add_port('port_left', left['network']['id'])
        port_right = self.add_port('port_right', right['network']['id'])

        self.router_left = self.add_router('router_left',
                                      public['network']['id'],
                                      subnet_left['subnet']['id'])
        self.router_right = self.add_router('router_right',
                                       public['network']['id'],
                                       subnet_right['subnet']['id'])

        try:
            self.create_resource(
                self.api.create_security_group_rule({
                    'security_group_rule': {
                        'direction': 'ingress',
                        'protocol': 'udp',
                        'security_group_id': port_left['port']['security_groups'][0]
                    }
                }))
        except Exception, e:
            LOG.debug('Error creating security group ' +
                      '(It could already exist)... continuing. %s' % e)
        try:
            self.create_resource(
                self.api.create_security_group_rule({
                    'security_group_rule': {
                        'direction': 'ingress',
                        'protocol': 'icmp',
                        'security_group_id': port_left['port']['security_groups'][0]
                    }
                }))
        except Exception, e:
            LOG.debug('Error creating security group ' +
                      '(It could already exist)... continuing. %s' % e)

        fip_left = self.create_resource(
            self.api.create_floatingip({
                'floatingip': {
                    'floating_network_id': public['network']['id'],
                    'port_id': port_left['port']['id'],
                    'tenant_id': 'admin'
                }
            }))
        self.fip_left = fip_left['floatingip']['floating_ip_address']

        fip_right = self.create_resource(
            self.api.create_floatingip({
                'floatingip': {
                    'floating_network_id': public['network']['id'],
                    'port_id': port_right['port']['id'],
                    'tenant_id': 'admin'
                }
            }))
        self.fip_right = fip_right['floatingip']['floating_ip_address']

    def get_fip_left(self):
        return self.fip_left

    def get_fip_right(self):
        return self.fip_right

    def get_router_extip_left(self):
        return self.router_left['router']['external_gateway_info']['external_fixed_ips'][0]['ip_address']

    def get_router_extip_right(self):
        return self.router_right['router']['external_gateway_info']['external_fixed_ips'][0]['ip_address']

    def add_router(self, name, external_net, internal_subnet):
        router = self.create_resource(
            self.api.create_router(
                    {'router': {'name': name,
                                'tenant_id': 'admin',
                                'external_gateway_info': {
                                    'network_id': external_net
                                }}}))

        self.api.add_interface_router(router['router']['id'],
                                      {'subnet_id': internal_subnet})

        self.addCleanup(self.api.remove_interface_router,
                        router['router']['id'],
                        {'subnet_id': internal_subnet})
        return router

    def add_network(self, name, cidr, gateway, external=False):
        network = self.create_resource(
            self.api.create_network({'network': {'name': name,
                                                 'admin_state_up': True,
                                                 'router:external': True,
                                                 'tenant_id': 'admin'}}))
        subnet = self.create_resource(
            self.api.create_subnet(
                {'subnet':
                    {'name': network['network']['name'] + '_subnet',
                     'network_id': network['network']['id'],
                     'ip_version': 4,
                     'cidr': cidr,
                     'gateway_ip': gateway,
                     'enable_dhcp': True}}))
        return (network, subnet)

    def add_port(self, name, network_id):
        return self.create_resource(
            self.api.create_port({'port': {'name': name,
                                           'network_id': network_id,
                                           'admin_state_up': True,
                                           'tenant_id': 'admin'}}))

VTM = VT_Networks_with_SG()
BM = BindingManager(None, VTM)

binding_multihost = {
    'description': 'spanning across 2 midolman',
    'bindings': [
        {'vport': 'port_left',
         'interface': {
             'definition': {'ipv4_gw': '10.0.0.1'},
             'hostname': 'midolman1',
             'type': 'vmguest'
         }},
        {'vport': 'port_right',
         'interface': {
             'definition': {'ipv4_gw': '20.0.0.1'},
             'hostname': 'midolman2',
             'type': 'vmguest'
         }}
    ]
}


@attr(version="v1.2.0")
@bindings(binding_multihost,
          binding_manager=BM)
def test_traceroute():
    """
    Title: Run a traceroute from one VM to another over a router

    Run a traceroute between two VMs. All hops should resolve correctly.
    """
    sender = BM.get_interface_on_vport('port_left')

    output = sender.execute("traceroute -n -N 1 -m 5 %s" % VTM.get_fip_right(),
                            sync=True)
    LOG.info("traceroute output %s" % output)

    hops_only = lambda (l): re.match("^\\s+[0-9]+", l)
    hops_ip = lambda(l): l.split()[1]
    hops = map(hops_ip, filter(hops_only, output.split('\n')))

    assert_that(hops[0], equal_to(sender.get_default_gateway_ip()),
                "First hop incorrect")
    assert_that(hops[1], equal_to(VTM.get_router_extip_right()),
                "Second hop incorrect")
    assert_that(hops[2], equal_to(VTM.get_fip_right()),
                "Third hop incorrect")

    for h in hops:
        assert_that(h, is_not(equal_to("*")),
                    "A traceroute probe failed %s" % output)
