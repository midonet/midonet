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

from hamcrest import assert_that
from hamcrest import equal_to
import logging
from mdts.lib.bindings import BindingManager
from mdts.lib import sandbox
from mdts.lib.vtm_neutron import NeutronTopologyManager
from mdts.services import service
from mdts.utils import conf
from mdts.utils.asserts import check_forward_flow
from mdts.utils.asserts import check_return_flow
from mdts.utils.utils import await_port_active
from mdts.utils.utils import bindings
from nose.tools import with_setup
import time

LOG = logging.getLogger(__name__)


# Two private networks (net_1 & net_2) and a public network
# Each network has two vms, one of them with a floating up.
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
        # public_x -> external port (associated to fip) on net x
        # private_x -> internal port on net x (for dynamic nat)
        public_1 = self.create_port('public_1', net1)
        self.create_port('private_1', net1)
        public_2 = self.create_port('public_2', net2)
        self.create_port('private_2', net2)

        self.add_router('router_1', public['id'], [subnet1])
        self.add_router('router_2', public['id'], [subnet2])

        # All ports share the same default SG, only need to set it once.
        self.create_sg_rule(public_1['security_groups'][0],
                            protocol='udp', port_range=[0, 65535])

        self.create_floating_ip('public_1_fip', public['id'],
                                public_1['id'])
        self.create_floating_ip('public_2_fip', public['id'],
                                public_2['id'])

    def add_router(self, name, external_net_id, internal_subnets):
        router = self.create_router(
            name, external_net_id=external_net_id)

        for internal_subnet in internal_subnets:
            self.add_router_interface(router, subnet=internal_subnet)

        return router

    def add_network(self, name, cidr, gateway, external=False):
        network = self.create_network(name, external)
        subnet = self.create_subnet(
            name + '_subnet', network, cidr, gateway_ip=gateway)
        return network, subnet

VTM = VT_Networks_with_SG()
BM = BindingManager(None, VTM)

binding_multihost = {
    'description': 'spanning across 2 midolman',
    'bindings': [
        {'vport': 'private_1',
         'interface': {
             'definition': {'ipv4_gw': '10.0.0.1'},
             'hostname': 'midolman1',
             'type': 'vmguest'
         }},
        {'vport': 'public_1',
         'interface': {
             'definition': {'ipv4_gw': '10.0.0.1'},
             'hostname': 'midolman1',
             'type': 'vmguest'
         }},
        {'vport': 'private_2',
         'interface': {
             'definition': {'ipv4_gw': '10.0.1.1'},
             'hostname': 'midolman2',
             'type': 'vmguest'
         }},
        {'vport': 'public_2',
         'interface': {
             'definition': {'ipv4_gw': '10.0.1.1'},
             'hostname': 'midolman2',
             'type': 'vmguest'
         }},
    ]
}

def install_packages(container_name, *packages):
    container = service.get_container_by_hostname(container_name)
    output, exec_id = container.exec_command(
        "sh -c 'env DEBIAN_FRONTEND=noninteractive apt-get install " + \
        "-qy --force-yes -o Dpkg::Options::=\"--force-confnew\" " + \
        "%s'" % (" ".join(packages)), stream=True)
    exit_code = container.check_exit_status(exec_id, output, timeout=60)
    if exit_code != 0:
        raise RuntimeError("Failed to update packages (%s)." % (packages))

def install():
    # Install new package so the new version is updated immediately after reboot
    install_packages("midolman1", "midolman/local", "midonet-tools/local")

def reset_sandbox():
    # Wipe out the sandbox and rebuild
    sandbox.kill_sandbox(conf.sandbox_name())
    sandbox.restart_sandbox('default_neutron+mitaka+compat',
                            conf.sandbox_name(),
                            'sandbox/override_compat',
                            'sandbox/provisioning/compat-provisioning.sh')
    # Reset cached containers and reload them (await for the new agent to be up)
    service.loaded_containers = None
    agent = service.get_container_by_hostname('midolman1')
    agent.wait_for_status('up')

@with_setup(install, reset_sandbox)
@bindings(binding_multihost,
          binding_manager=BM)
def test_compat_flowstate():
    """
    Title: Tests that flow state changes are backwards compatible

    The topology is set up in such a way that both conntrack
    and NAT flow state is generated.

    Send nonfip-to-fip udp packets between two agents in both directions
    Restart one of the agents with the package built and stored on the override
    Verify that previous flows still work in both directions
    Verify that new flows can be created in both directions
    """

    # vms on midolman1
    public_vm1 = BM.get_interface_on_vport('public_1')
    private_vm1 = BM.get_interface_on_vport('private_1')
    # vms on midolman2
    public_vm2 = BM.get_interface_on_vport('public_2')
    private_vm2 = BM.get_interface_on_vport('private_2')

    fip1 = VTM.get_resource('public_1_fip')['floatingip']['floating_ip_address']
    fip2 = VTM.get_resource('public_2_fip')['floatingip']['floating_ip_address']

    agent = service.get_container_by_hostname('midolman1')

    snat_1 = check_forward_flow(private_vm1, public_vm2, fip2, 50000, 80)
    check_return_flow(public_vm2, private_vm1, snat_1['ip'], snat_1['port'], 50000, 80)

    snat_2 = check_forward_flow(private_vm2, public_vm1, fip1, 50000, 80)
    check_return_flow(public_vm1, private_vm2, snat_2['ip'], snat_2['port'], 50000, 80)

    # When: rebooting the agent with the updated package (waiting for the ports
    #        to be up).
    public_vm1_id = VTM.get_resource('public_1')['port']['id']
    private_vm1_id = VTM.get_resource('private_1')['port']['id']
    agent.stop(wait=True)
    await_port_active(public_vm1_id, active=False)
    await_port_active(private_vm1_id, active=False)
    agent.start(wait=True)
    await_port_active(public_vm1_id, active=True)
    await_port_active(private_vm1_id, active=True)

    # Eventually: it may take some time before we gather flow state from storage
    #             keep trying until we succeed.
    attempts = 10
    while True:
        try:
            # Check that flow state keys are fetched from storage
            check_return_flow(public_vm2, private_vm1, snat_1['ip'], snat_1['port'], 50000, 80)
            check_return_flow(public_vm1, private_vm2, snat_2['ip'], snat_2['port'], 50000, 80)
            break
        except Exception:
            if attempts > 0:
                time.sleep(5)
                attempts -= 1
            else:
                raise

    # And: Check that the same port is used on same forward flow
    assert_that(check_forward_flow(private_vm1, public_vm2, fip2, 50000, 80),
                equal_to(snat_1))
    assert_that(check_forward_flow(private_vm2, public_vm1, fip1, 50000, 80),
                equal_to(snat_2))

    # And: we can create new flows between two agents on different versions
    #      on both directions.
    snat_1 = check_forward_flow(private_vm1, public_vm2, fip2, 50001, 81)
    check_return_flow(public_vm2, private_vm1, snat_1['ip'], snat_1['port'], 50001, 81)

    snat_2 = check_forward_flow(private_vm2, public_vm1, fip1, 50001, 81)
    check_return_flow(public_vm1, private_vm2, snat_2['ip'], snat_2['port'], 50001, 81)
