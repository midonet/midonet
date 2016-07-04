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

from mdts.lib import sandbox
from mdts.lib.vtm_neutron import NeutronTopologyManager
from mdts.lib.bindings import BindingManager
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
        private_port = self.add_port('private_port', net1['network']['id'])
        public_port = self.add_port('public_port', net2['network']['id'])
        other_port = self.add_port('other_port', net3['network']['id'])

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
                        'port_range_min': 0,
                        'port_range_max': 65535,
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
         }},
        {'vport': 'other_port',
         'interface': {
             'definition': {'ipv4_gw': '10.0.2.1'},
             'hostname': 'midolman3',
             'type': 'vmguest'
         }},
    ]
}


def install():
    # Install new package so the new version is updated immediately after reboot
    agent = service.get_container_by_hostname('midolman1')
    output, exec_id = agent.exec_command(
            "apt-get install -qy --force-yes "
            "-o Dpkg::Options::=\"--force-confnew\" "
            "midolman/local midonet-tools/local", stream=True)
    exit_code = agent.check_exit_status(exec_id, output, timeout=60)
    if exit_code != 0:
        raise RuntimeError("Failed to update package.")


def cleanup():
    agent = service.get_container_by_hostname('midolman1')
    # We wait just to make sure that it's been unregistered
    agent.stop(wait=True)
    # Wipe out the container
    sandbox.remove_container(agent)
    # Restart sandbox, the --no-recreate flag will spawn only missing containers
    sandbox.restart_sandbox('default_neutron+kilo+compat',
                            conf.sandbox_name(),
                            'sandbox/override_compat')
    # Reset cached containers and reload them (await for the new agent to be up)
    service.loaded_containers = None
    agent = service.get_container_by_hostname('midolman1')
    agent.wait_for_status('up')


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


@bindings(binding_multihost,
          binding_manager=BM)
@with_setup(install, cleanup)
def test_compat_flowstate():
    """
    Title: Tests that flow state changes are backwards compatible

    The topology is set up in such a way that both conntrack
    and NAT flow state is generated.

    Send nonfip-to-fip udp packets between two agents and return packets
    Unbind the public port and bind it to a different vm
    Verify that previous flows still work in both directions
    """

    # vm on midolman1
    private_port_vm = BM.get_interface_on_vport('private_port')
    # vm on midolman2
    public_port_vm = BM.get_interface_on_vport('public_port')
    free_port_vm = BM.get_interface_on_vport('other_port')

    public_port_id = VTM.get_resource('public_port')['port']['id']
    fip = VTM.get_resource('public_port_fip')['floatingip']['floating_ip_address']

    agent2 = service.get_container_by_hostname('midolman2')
    agent3 = service.get_container_by_hostname('midolman3')

    # Generate flow state
    snat = check_forward_flow(private_port_vm, public_port_vm, fip, 50000, 80)
    check_return_flow(public_port_vm, private_port_vm, snat['ip'], snat['port'], 50000, 80)

    # Unbind/bind port to a different host
    agent2.unbind_port(public_port_vm)
    agent3.unbind_port(free_port_vm)
    agent3.bind_port(free_port_vm, public_port_id)

    # Check that the new host can read the previous flowstate and send the same return flow
    check_return_flow(free_port_vm, private_port_vm, snat['ip'], snat['port'], 50000, 80)