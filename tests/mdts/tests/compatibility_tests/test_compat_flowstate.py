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

from mdts.lib.vtm_neutron import NeutronTopologyManager
from mdts.lib.bindings import BindingManager
from mdts.services import service
from mdts.tests.utils.asserts import *
from mdts.tests.utils.utils import await_port_active
from mdts.tests.utils.utils import bindings
from mdts.tests.utils.utils import wait_on_futures

from hamcrest import *

import logging
import time

LOG = logging.getLogger(__name__)


# Two private networks (net_1 & net_2) and a public network
# Each network has one vm. The vm on net_2 has a floating ip.
# The default security group allows all ingress udp traffic.
# The default rules will generate conntrack flow state.
# Outgoing traffic from net_1 will generate NAT flow state.
class VT_Networks_with_SG(NeutronTopologyManager):

    def build(self, binding_data=None):
        (public, public_subnet) = self.add_network('public', '1.0.0.0/8',
                                                   '1.0.0.1', external=True)
        (net1, subnet1) = self.add_network('net_1', '10.0.0.0/24', '10.0.0.1')
        (net2, subnet2) = self.add_network('net_2', '10.0.1.0/24', '10.0.1.1')
        port1 = self.add_port('port_1', net1['network']['id'])
        port2 = self.add_port('port_2', net2['network']['id'])

        self.add_router('router_1',
                        public['network']['id'],
                        [subnet1['subnet']['id']])
        self.add_router('router_2',
                        public['network']['id'],
                        [subnet2['subnet']['id']])

        try:
            self.create_resource(
                self.api.create_security_group_rule({
                    'security_group_rule': {
                        'direction': 'ingress',
                        'port_range_min': 0,
                        'port_range_max': 10000,
                        'protocol': 'udp',
                        'security_group_id': port1['port']['security_groups'][0]
                    }
                })
            )
        except Exception as e:
            LOG.debug('Error creating security group ' +
                      '(It could already exist)... continuing. %s' % e)

        self.create_resource(
            self.api.create_floatingip({
                'floatingip': {
                    'floating_network_id': public['network']['id'],
                    'port_id': port1['port']['id'],
                    'tenant_id': 'admin'
                }
            }), name='fip_port_1')

        self.create_resource(
            self.api.create_floatingip({
                'floatingip': {
                    'floating_network_id': public['network']['id'],
                    'port_id': port2['port']['id'],
                    'tenant_id': 'admin'
                }
            }), name='fip_port_2')

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
            self.set_resource(name+"_internal_if", router_if)
            router_if_port = self.api.show_port(router_if['port_id'])
            router_if_ip = router_if_port['port']['fixed_ips'][0]['ip_address']
            self.set_resource(name+"_internal_ip", router_if_ip)
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
                          {'name': network['network']['name']+'_subnet',
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

binding_multihost = {
    'description': 'spanning across 2 midolman',
    'bindings': [
        {'vport': 'port_1',
         'interface': {
             'definition': {'ipv4_gw': '10.0.0.1'},
             'hostname': 'midolman1',
             'type': 'vmguest'
         }},
        {'vport': 'port_2',
         'interface': {
             'definition': {'ipv4_gw': '10.0.1.1'},
             'hostname': 'midolman2',
             'type': 'vmguest'
         }}
    ]
}


def async_check_flow(vms, port):
    results = []
    for vm in vms:
        recv_filter = 'udp and port %d and ip dst %s' % (port, vm.get_ip())
        f = async_assert_that(vm, receives(recv_filter, within_sec(20)))
        results.append(f)
    return results


@bindings(binding_multihost,
          binding_manager=BM)
def test_compat_flowstate():
    """
    Title: Run a 1 hour workload that generates a lot of flow state

    Send 10 UDP packets per second for an hour, changing the src and dst port
    for each packet. The topology is set up in such a way that both conntrack
    and NAT flow state is generated.
    """
    vm1 = BM.get_interface_on_vport('port_1')
    vm2 = BM.get_interface_on_vport('port_2')

    fip1 = VTM.get_resource('fip_port_1')['floatingip']['floating_ip_address']
    fip2 = VTM.get_resource('fip_port_2')['floatingip']['floating_ip_address']

    # Install new package so the new version is updated immediately after reboot
    # Install on midolman1
    agent = service.get_container_by_hostname('midolman1')
    agent.exec_command("apt-get install -qy --force-yes "
                       "midolman/local midonet-tools/local")
    BM.addCleanup(agent.restart)
    BM.addCleanup(agent.exec_command,
                  "apt-get install -qy --force-yes "
                  "midolman=2:5.1.0 midonet-tools=2:5.1.0")

    # Expect: Both vms receive the packet
    results = async_check_flow(vms=[vm1, vm2], port=80)
    # When: Sending udp packet vm1 -> vm2 and vm2 -> vm1
    vm1.execute('hping3 -c 1 -q -2 -s 50000 -p 80 %s' % fip2)
    vm2.execute('hping3 -c 1 -q -2 -s 50000 -p 80 %s' % fip1)
    wait_on_futures(results)

    # And expect: Both vms receive return traffic
    results = async_check_flow(vms=[vm1, vm2], port=50000)
    # When: sending return flows
    vm1.execute('hping3 -c 1 -q -2 -s 80 -p 50000 %s' % fip2)
    vm2.execute('hping3 -c 1 -q -2 -s 80 -p 50000 %s' % fip1)
    wait_on_futures(results)

    # After: rebooting the agent with the updated package (waiting for the port
    #        to be up).
    port_id = VTM.get_resource('port_1')['port']['id']
    agent.stop(wait=True)
    await_port_active(port_id, active=False)
    agent.start(wait=False)

    # Eventually: it may take some time before we gather flow state from storage
    #             keep trying until we succeed.
    attempts = 10
    while True:
        try:
            # Expect: both vms still receive the packet
            results = async_check_flow(vms=[vm2], port=50000)
            # When: sending return flows again
            vm1.execute('hping3 -c 1 -q -2 -s 80 -p 50000 %s' % fip2)
            wait_on_futures(results)
            break
        except:
            if attempts > 0:
                time.sleep(5)
                attempts -= 1
            else:
                raise

    # And expect: both vms receive the packet
    results = async_check_flow(vms=[vm1, vm2], port=81)
    # When: Using a new src/dst port to generate new flow state
    vm1.execute('hping3 -c 1 -q -2 -s 50001 -p 81 %s' % fip2)
    vm2.execute('hping3 -c 1 -q -2 -s 50001 -p 81 %s' % fip1)
    wait_on_futures(results)

    # And expect: both vms still receive the packet
    results = async_check_flow(vms=[vm1, vm2], port=50001)
    # When: sending return flow
    vm1.execute('hping3 -c 1 -q -2 -s 81 -p 50001 %s' % fip2)
    vm2.execute('hping3 -c 1 -q -2 -s 81 -p 50001 %s' % fip1)
    wait_on_futures(results)






