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

from mdts.lib.physical_topology_manager import PhysicalTopologyManager
from mdts.services import service
from mdts.tests.utils.asserts import *
from mdts.tests.utils.utils import bindings
from mdts.tests.utils.utils import wait_on_futures

import neutronclient.neutron.client as neutron

import logging
import time

LOG = logging.getLogger(__name__)

PTM = PhysicalTopologyManager('../topologies/mmm_physical_test_bridge.yaml')


@with_setup(PTM.build, PTM.destroy)
def test_icmp():
    """
    Title: ICMP reachability over neutron network

    Scenario 1:
    When: a VM sends ICMP echo request with ping command
    Then: the receiver VM should receive the ICMP echo packet.
    And: the ping command succeeds
    """

    # Building physical topology
    host1 = service.get_container_by_hostname('midolman1')
    host2 = service.get_container_by_hostname('midolman2')
    vm1data = {'hw_addr': 'aa:bb:cc:00:00:11',
               'ipv4_addr': ['172.16.1.2/24'],
               'ipv4_gw': '172.16.1.1'}
    vm2data = {'hw_addr': 'aa:bb:cc:00:00:22',
               'ipv4_addr': ['172.16.1.3/24'],
               'ipv4_gw': '172.16.1.1'}
    vm1 = host1.create_vmguest(**vm1data)
    vm2 = host2.create_vmguest(**vm2data)

    # Building virtual topology
    neutron_ip = service.get_container_by_hostname('neutron').get_ip_address()
    keystone_ip = service.get_container_by_hostname('keystone').get_ip_address()
    # Create topology with neutron
    api = neutron.Client('2.0',
                         auth_url='http://%s:35357/v2.0' % keystone_ip,
                         endpoint_url='http://%s:9696' % neutron_ip,
                         tenant_name='admin',
                         username='admin',
                         password='admin')

    # Create network
    network = api.create_network(
        {'network':
             {'name': 'demo', 'admin_state_up': True, 'tenant_id': 'admin'}})['network']

    subnet = api.create_subnet({'subnet': {'name': 'demo_subnet',
                                           'network_id': network['id'],
                                           'ip_version': 4,
                                           'cidr': '172.16.1.0/24',
                                           'enable_dhcp': False}})

    # Create ports
    port1json = {'port': {'name': 'port1',
                          'network_id': network['id'],
                          'admin_state_up': True,
                          'mac_address': 'aa:bb:cc:00:00:11',
                          'fixed_ips': [{ 'ip_address': '172.16.1.2'}]}}
    port1 = api.create_port(port1json)['port']
    LOG.debug(port1)

    port2json = {'port': {'name': 'port2',
                          'network_id': network['id'],
                          'admin_state_up': True,
                          'mac_address': 'aa:bb:cc:00:00:22',
                          'fixed_ips': [{ 'ip_address': '172.16.1.3'}]}}
    port2 = api.create_port(port2json)['port']
    LOG.debug(port2)

    # Bind virtual and physical topology

    host1.bind_port(vm1, port1['id'])
    host2.bind_port(vm2, port2['id'])

    # Test ping works

    f1 = async_assert_that(vm2,
                           receives('dst host %s and icmp' % vm2.get_ip(),
                                    within_sec(10)))
    f2 = vm1.ping4(vm2)

    wait_on_futures([f1, f2])

    # Destroy topology
    vm1.destroy()
    vm2.destroy()
    api.delete_port(port1['id'])
    api.delete_port(port2['id'])
    api.delete_network(network['id'])
