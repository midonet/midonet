#
# Copyright 2015 Midokura SARL
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
#
from nose import with_setup
from nose.tools import nottest

from mdts.lib.bindings import BindingManager

from mdts.lib.topology_manager import TopologyManager
from mdts.lib.vtm_neutron import NeutronTopologyManager
from mdts.services import service
from mdts.tests.utils.asserts import *
from mdts.tests.utils.utils import bindings, get_neutron_api
from mdts.tests.utils.utils import wait_on_futures

import neutronclient.neutron.client as neutron

import logging

LOG = logging.getLogger(__name__)


"""
This example shows how to execute a test with a predifined physical topology
specified in the topologies/mmm_physical_test_bridge.yaml.

The virtual topology is created inside the test with direct calls to the
Neutron API. It uses the helper method in the NeutronTopologyManager to create
any kind of resources (networks, subnets and ports in this test).
This helper method schedules the deletion of resources created to be executed
during the teardown of the topology (which is executed at the end of the test
or if there's an exception) and ensures that no resources created are left
orphaned after the test.
"""

PTM = TopologyManager()
VTM = NeutronTopologyManager()

def setup_1():
    PTM.build()
    VTM.build()

def destroy_1():
    VTM.destroy()
    PTM.destroy()

@nottest
@with_setup(setup_1, destroy_1)
def test_icmp_topology_in_test():
    # Scenario:
    # When: a VM sends ICMP echo request with ping command
    # Then: the receiver VM should receive the ICMP echo packet.
    # And: the ping command succeeds

    # Building physical topology
    host1 = service.get_container_by_hostname('midolman1')
    host2 = service.get_container_by_hostname('midolman2')
    PTM.add_host_to_tunnel_zone('midolman1', 'tztest1')
    PTM.add_host_to_tunnel_zone('midolman2', 'tztest1')

    vm1data = {'hw_addr': 'aa:bb:cc:00:00:11',
               'ipv4_addr': ['172.16.1.2/24'],
               'ipv4_gw': '172.16.1.1'}
    vm2data = {'hw_addr': 'aa:bb:cc:00:00:22',
               'ipv4_addr': ['172.16.1.3/24'],
               'ipv4_gw': '172.16.1.1'}
    vm1 = host1.create_vmguest(**vm1data)
    vm2 = host2.create_vmguest(**vm2data)

    PTM.addCleanup(host1.destroy_vmguest, vm1)
    PTM.addCleanup(host2.destroy_vmguest, vm2)

    # Building virtual topology
    api = get_neutron_api()

    # Create network
    network = VTM.create_resource(
        api.create_network(
            {'network':
                {'name': 'demo',
                 'admin_state_up': 'True',
                 'tenant_id': 'admin'}}
        )
    )

    VTM.create_resource(
        api.create_subnet(
            {'subnet':
                {'name': 'demo_subnet',
                 'network_id': network['network']['id'],
                 'ip_version': 4,
                 'cidr': '172.16.1.0/24',
                 'enable_dhcp': False}}
        )
    )

    # Create ports
    port1json = {'port': {'name': 'port1',
                          'network_id': network['network']['id'],
                          'admin_state_up': True,
                          'mac_address': 'aa:bb:cc:00:00:11',
                          'fixed_ips': [{'ip_address': '172.16.1.2'}]}}
    port1 = VTM.create_resource(api.create_port(port1json))
    LOG.debug(port1)

    port2json = {'port': {'name': 'port2',
                          'network_id': network['network']['id'],
                          'admin_state_up': True,
                          'mac_address': 'aa:bb:cc:00:00:22',
                          'fixed_ips': [{'ip_address': '172.16.1.3'}]}}
    port2 = VTM.create_resource(api.create_port(port2json))
    LOG.debug(port2)

    # Bind virtual and physical topology

    host1.bind_port(vm1, port1['port']['id'])
    host2.bind_port(vm2, port2['port']['id'])

    # Test ping works

    f1 = async_assert_that(vm2,
                           receives('dst host %s and icmp' % vm2.get_ip(),
                                    within_sec(10)))
    f2 = vm1.ping4(vm2)

    wait_on_futures([f1, f2])


"""
This example shows how to execute a test with the topology created outside the
test itself so it can be reused on other tests of the same module.

The virtual topology in this case is also executed separately from the test
itself to be able to reuse it in other tests as we will see with a bindings
example later on.

This examples will server to see how the same virtual topology can test
different scenarios by binding the virtual ports to different vms on different
hosts.
"""

class VT_one_net_two_ports(NeutronTopologyManager):

    def build(self, data=None):
        # Create network
        network = self.create_resource(
            self.api.create_network(
                {'network':
                    {'name': 'demo',
                     'admin_state_up': 'True',
                     'tenant_id': 'admin'}}
            )
        )

        self.create_resource(
            self.api.create_subnet(
                {'subnet':
                    {'name': 'demo_subnet',
                     'network_id': network['network']['id'],
                     'ip_version': 4,
                     'cidr': '172.16.1.0/24',
                     'enable_dhcp': False}}
            )
        )

        # Create ports
        port1json = {'port': {'name': 'port1',
                              'network_id': network['network']['id'],
                              'admin_state_up': True,
                              'mac_address': 'aa:bb:cc:00:00:11',
                              'fixed_ips': [{'ip_address': '172.16.1.2'}]}}
        port1 = self.create_resource(self.api.create_port(port1json))
        LOG.debug(port1)

        port2json = {'port': {'name': 'port2',
                              'network_id': network['network']['id'],
                              'admin_state_up': True,
                              'mac_address': 'aa:bb:cc:00:00:22',
                              'fixed_ips': [{'ip_address': '172.16.1.3'}]}}
        port2 = self.create_resource(self.api.create_port(port2json))
        LOG.debug(port2)

class PT_two_vms_on_separate_hosts(TopologyManager):

    def build(self, data=None):
        # Building physical topology with two vms on midolman 1 and one vm on
        # midolman2
        host1 = service.get_container_by_hostname('midolman1')
        host2 = service.get_container_by_hostname('midolman2')
        self.add_host_to_tunnel_zone('midolman1', 'tztest2')
        self.add_host_to_tunnel_zone('midolman2', 'tztest2')
        vm1data = {'hw_addr': 'aa:bb:cc:00:00:11',
                   'ipv4_addr': ['172.16.1.2/24'],
                   'ipv4_gw': '172.16.1.1'}
        vm2data = {'hw_addr': 'aa:bb:cc:00:00:22',
                   'ipv4_addr': ['172.16.1.3/24'],
                   'ipv4_gw': '172.16.1.1'}
        vm1 = host1.create_vmguest(**vm1data)
        vm2 = host1.create_vmguest(**vm2data)
        vm3 = host2.create_vmguest(**vm2data)

        self.addCleanup(host1.destroy_vmguest, vm1)
        self.addCleanup(host1.destroy_vmguest, vm2)
        self.addCleanup(host2.destroy_vmguest, vm3)

        self.set_resource('vm1_host1', vm1)
        self.set_resource('vm2_host1', vm2)
        self.set_resource('vm2_host2', vm3)

PTM2 = PT_two_vms_on_separate_hosts()
VTM2 = VT_one_net_two_ports()

def setup_2():
    PTM2.build()
    VTM2.build()

def destroy_2():
    VTM2.destroy()
    PTM2.destroy()

@nottest
@with_setup(setup_2, destroy_2)
def test_icmp_topology_out_test_single_compute():
    # Query resources created on the managers
    vm1 = PTM2.get_resource('vm1_host1')
    vm2 = PTM2.get_resource('vm2_host1')

    port1 = VTM2.get_resource('port1')
    port2 = VTM2.get_resource('port2')

    # Bind virtual and physical topology
    vm1.compute_host.bind_port(vm1, port1['port']['id'])
    vm2.compute_host.bind_port(vm2, port2['port']['id'])

    # Test ping works
    f1 = async_assert_that(vm2,
                           receives('dst host %s and icmp' % vm2.get_ip(),
                                    within_sec(10)))
    f2 = vm1.ping4(vm2)

    wait_on_futures([f1, f2])

@nottest
@with_setup(setup_2, destroy_2)
def test_icmp_topology_out_test_two_computes():
    # Query resources created on the managers
    vm1 = PTM2.get_resource('vm1_host1')
    vm2 = PTM2.get_resource('vm2_host2')

    port1 = VTM2.get_resource('port1')
    port2 = VTM2.get_resource('port2')

    # Bind virtual and physical topology
    vm1.compute_host.bind_port(vm1, port1['port']['id'])
    vm2.compute_host.bind_port(vm2, port2['port']['id'])

    # Test ping works
    f1 = async_assert_that(vm2,
                           receives('dst host %s and icmp' % vm2.get_ip(),
                                    within_sec(10)))
    f2 = vm1.ping4(vm2)

    wait_on_futures([f1, f2])

"""
This example will show you how to compact the two scenarios above into
a single test with the bindings mechanism implemented in MDTS. As you can see,
both tests are quite similar in the sense of what are they testing (only one
line changed).
However, the scenario is quite different as in the first scenario both vms
(and both virtual ports attached) are hosted on the same compute node.
This is how the majority of tests in mdts are tested as you usually want to
test a single feature in a set of different scenarios.
"""

binding_singlehost = {
    'description': 'two vms on one MM',
    'bindings': [
        {'vport': 'port1',
         'interface': 'vm1_host1'},
        {'vport': 'port2',
         'interface': 'vm2_host1'}
    ]
}

binding_multihost = {
    'description': 'two vms on different MMs',
    'bindings': [
        {'vport': 'port1',
         'interface': 'vm1_host1'},
        {'vport': 'port2',
         'interface': 'vm2_host2'}
    ]
}

BM = BindingManager(PTM2, VTM2)

@nottest
@bindings(binding_singlehost,
          binding_multihost,
          binding_manager = BM)
def test_icmp_autobind():
    vm1 = BM.get_interface_on_vport('port1')
    vm2 = BM.get_interface_on_vport('port2')
    # Test ping works
    f1 = async_assert_that(vm2,
                           receives('dst host %s and icmp' % vm2.get_ip(),
                                    within_sec(10)))
    f2 = vm1.ping4(vm2)

    wait_on_futures([f1, f2])


"""
Last but not least. You can also forget about managing the physical topology all
together and let the binding manager create the vms for you. Just need to define
the interface parameters in a dictionary, and pass it to the the interface
definition in the binding alongside the compute host where that interface
will be created and the type of interface (vmguest, trunk or provided).
Notice that the BindingManager is not passed an instance
of a topology manager.
"""

vm1_def = {
    'hw_addr': 'aa:bb:cc:00:00:11',
    'ipv4_addr': ['172.16.1.2/24'],
    'ipv4_gw': '172.16.1.1'
}

vm2_def = {
    'hw_addr': 'aa:bb:cc:00:00:22',
    'ipv4_addr': ['172.16.1.3/24'],
    'ipv4_gw': '172.16.1.1'
}


binding_singlehost_inbinding = {
    'description': 'two vms on one MM with vms in binding',
    'bindings': [
        {'vport': 'port1',
         'interface': {
             'definition': vm1_def,
             'hostname': 'midolman1',
             'type': 'vmguest'
         }},
        {'vport': 'port2',
         'interface': {
             'definition': vm2_def,
             'hostname': 'midolman1',
             'type': 'vmguest'
         }}
    ]
}

# Here we use the same vm definition but we create those vms on separate hosts
# as oposed to the previous binding definition
binding_multihost_inbinding = {
    'description': 'two vms on multiple MM with vms in binding',
    'bindings': [
        {'vport': 'port1',
         'interface': {
             'definition': vm1_def,
             'hostname': 'midolman1',
             'type': 'vmguest'
         }},
        {'vport': 'port2',
         'interface': {
             'definition': vm2_def,
             'hostname': 'midolman2',
             'type': 'vmguest'
         }}
    ]
}


BM2 = BindingManager(None, VTM2)

@nottest
@bindings(binding_singlehost_inbinding,
          binding_multihost_inbinding,
          binding_manager=BM2)
def test_icmp_autobind_vm_in_binding():
    vm1 = BM2.get_interface_on_vport('port1')
    vm2 = BM2.get_interface_on_vport('port2')
    # Test ping works
    f1 = async_assert_that(vm2,
                           receives('dst host %s and icmp' % vm2.get_ip(),
                                    within_sec(10)))
    f2 = vm1.ping4(vm2)

    wait_on_futures([f1, f2])
