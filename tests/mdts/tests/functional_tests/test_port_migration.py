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

import copy

from mdts.lib import sandbox
from mdts.lib.bindings import BindingManager
from mdts.lib.bindings import BindingType
from mdts.lib.vtm_neutron import NeutronTopologyManager
from mdts.services import service
from mdts.tests.utils.asserts import *
from mdts.tests.utils.utils import await_port_active
from mdts.tests.utils.utils import bindings
from mdts.tests.utils.utils import wait_on_futures
from mdts.tests.utils import conf

from hamcrest import *
from nose.tools import with_setup

import logging
import time

LOG = logging.getLogger(__name__)


# Two private networks (net_1 & net_2) and a public network
# Each network has one vm. One of them with a floating ip, the other private.
# The default security group allows all ingress udp traffic.
# The default rules will generate conntrack flow state.
# Outgoing traffic from the vm without a floating ip will
# generate NAT flow state.
class VT_Networks_with_SG(NeutronTopologyManager):

    def build(self, binding_data=None):
        (public, public_subnet) = self.add_network('public', '1.0.0.0/8',
                                                   '1.0.0.1', external=True)
        (net1, subnet1) = self.add_network('net_1', '10.0.0.0/24', '10.0.0.1')
        (net2, subnet2) = self.add_network('net_2', '10.0.1.0/24', '10.0.1.1')
        (net3, subnet3) = self.add_network('net_3', '10.0.2.0/24', '10.0.2.1')
        # public_port -> external port (associated to fip) on net x
        # private_port -> internal port on net x (for dynamic nat)
        self.add_port('private_port', net1['network']['id'])
        public_port = self.add_port('public_port', net2['network']['id'])

        self.add_router('router_1',
                        public['network']['id'],
                        [subnet1['subnet']['id']])
        self.add_router('router_2',
                        public['network']['id'],
                        [subnet2['subnet']['id']])
        self.add_router('router_3',
                        public['network']['id'],
                        [subnet3['subnet']['id']])

        # All ports share the same default SG, only need to set it once.
        self.create_resource(
                self.api.create_security_group_rule({
                    'security_group_rule': {
                        'direction': 'ingress',
                        'protocol': 'udp',
                        'security_group_id': public_port['port']['security_groups'][0]
                    }
                })
            )

        self.create_resource(
            self.api.create_floatingip({
                'floatingip': {
                    'floating_network_id': public['network']['id'],
                    'port_id': public_port['port']['id'],
                    'tenant_id': 'admin'
                }
            }), name='public_port_fip')

    def add_router(self, name, external_net, internal_subnets):
        router_def = {'router':
                          {'name': name,
                           'tenant_id': 'admin',
                           }}
        if external_net:
            router_def['router']['external_gateway_info'] = \
                {'network_id': external_net}

        router = self.create_resource(
            self.api.create_router(router_def))

        for internal_subnet in internal_subnets:
            router_if = self.api.add_interface_router(
                router['router']['id'], {'subnet_id': internal_subnet})
            self.set_resource(name + "_internal_if", router_if)
            router_if_port = self.api.show_port(router_if['port_id'])
            router_if_ip = router_if_port['port']['fixed_ips'][0]['ip_address']
            self.set_resource(name + "_internal_ip", router_if_ip)
            self.addCleanup(self.api.remove_interface_router,
                            router['router']['id'],
                            {'subnet_id': internal_subnet})

        return router

    def add_network(self, name, cidr, gateway, external=False):
        network = self.create_resource(
            self.api.create_network({'network': {'name': name,
                                                 'admin_state_up': True,
                                                 'router:external': external,
                                                 'tenant_id': 'admin'}}))

        subnet_def = {'subnet':
                         {'name': network['network']['name'] + '_subnet',
                          'network_id': network['network']['id'],
                          'ip_version': 4,
                          'cidr': cidr,
                          'gateway_ip': gateway,
                          'enable_dhcp': True}}

        subnet = self.create_resource(self.api.create_subnet(subnet_def))
        return network, subnet

    def add_port(self, name, network_id):
        return self.create_resource(
            self.api.create_port({'port': {'name': name,
                                           'network_id': network_id,
                                           'admin_state_up': True,
                                           'tenant_id': 'admin'}}))

VTM = VT_Networks_with_SG()
BM = BindingManager(None, VTM)

binding_multihost_api = {
    'description': 'using the midonet api to bind/unbind',
    'bindings': [
        {'vport': 'private_port',
         'interface': {
             'definition': {'ipv4_gw': '10.0.0.1'},
             'hostname': 'midolman1',
             'type': 'vmguest'
         }},
        {'vport': 'public_port',
         'interface': {
             'definition': {'ipv4_gw': '10.0.1.1'},
             'hostname': 'midolman2',
             'type': 'vmguest'
         }}
    ],
    'binding_type': BindingType.API
}

binding_multihost_mmctl = copy.deepcopy(binding_multihost_api)
binding_multihost_mmctl.update({
    'description': 'using mm-ctl command to bind/unbind',
    'binding_type': BindingType.MMCTL
})


def check_forward_flow(src_vm, dst_vm, fip, src_port, dst_port):
    # Expect: VM with fip to receive the packet
    recv_filter = 'udp and port %d and ip dst %s' % (dst_port, dst_vm.get_ip())
    f = async_assert_that(dst_vm, receives(recv_filter, within_sec(10)))
    # When: Sending udp packet
    #  src_vm (internal) -> dst_vm (fip)
    src_vm.execute('hping3 -c 1 -q -2 -s %s -p %s %s' %
                   (src_port, dst_port, fip))
    wait_on_futures([f])

    # tcpdump format:
    # date net_proto src_ip.src_port > dst_ip.dst_port: transp_proto [...]
    output = dst_vm.get_last_tcpdump_output()
    snat_ip = output.split(' ')[2].rsplit('.', 1)[0]
    snat_port = output.split(' ')[2].rsplit('.', 1)[1]

    return {'ip': snat_ip, 'port': snat_port}


def check_return_flow(src_vm, dst_vm, snat_ip, snat_port, dst_port, src_port):
    # And expect: Internal VM receives return traffic
    recv_filter = 'udp and port %d and ip dst %s' % (dst_port, dst_vm.get_ip())
    f = async_assert_that(dst_vm, receives(recv_filter, within_sec(10)))
    # When: sending return flows
    src_vm.execute('hping3 -c 1 -q -2 -s %s -p %s %s' %
                   (src_port, snat_port, snat_ip))
    wait_on_futures([f])


@bindings(binding_multihost_api,
          binding_multihost_mmctl,
          binding_manager=BM)
def test_simple_port_migration():
    """
    Title: Tests that flow state changes are backwards compatible

    The topology is set up in such a way that both conntrack
    and NAT flow state is generated.

    Send nonfip-to-fip udp packets between two agents and return packets
    Unbind the public port and bind it to a different vm
    Verify that previous flows still work in both directions
    """

    binding_type = BM.get_binding_data()['binding_type']

    agent2 = service.get_container_by_hostname('midolman2')
    agent3 = service.get_container_by_hostname('midolman3')

    # vm on midolman1
    private_interface_vm = BM.get_interface_on_vport('private_port')
    # vm on midolman2
    public_interface_vm = BM.get_interface_on_vport('public_port')
    # port to be migrated
    public_port = VTM.get_resource('public_port')['port']

    # new free vm on midolman3
    free_interface_vm_data = {
        'hw_addr': public_port['mac_address'],
        'ipv4_addr': [public_port['fixed_ips'][0]['ip_address'] + '/24'],
        'ipv4_gw': '10.0.1.1'}
    free_interface_vm = agent3.create_vmguest(**free_interface_vm_data)
    VTM.addCleanup(agent3.destroy_vmguest, free_interface_vm)

    fip = VTM.get_resource('public_port_fip')['floatingip']['floating_ip_address']

    # Generate flow state
    snat = check_forward_flow(
        private_interface_vm, public_interface_vm, fip, 50000, 80)
    check_return_flow(
        public_interface_vm, private_interface_vm, snat['ip'], snat['port'], 50000, 80)

    # Unbind/bind port to a different host
    if binding_type == BindingType.API:
        agent2.unbind_port(public_interface_vm, type=binding_type)
    agent3.bind_port(free_interface_vm, public_port['id'], type=binding_type)

    await_port_active(public_port['id'], active=True)

    check_return_flow(
        free_interface_vm, private_interface_vm, snat['ip'], snat['port'], 50000, 80)
