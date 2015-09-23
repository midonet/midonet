# Copyright 2014 Midokura SARL
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

"""
Tests if Midolman agent / interface status update
"""
import random

from hamcrest import assert_that
from hamcrest import none
from hamcrest import not_none
from nose.plugins.attrib import attr

from mdts.services import service
from mdts.tests.utils.utils import get_midonet_api
from mdts.lib.binding_manager import BindingManager
from mdts.lib.physical_topology_manager import PhysicalTopologyManager
from mdts.lib.virtual_topology_manager import VirtualTopologyManager
from mdts.tests.utils.asserts import receives, async_assert_that
from mdts.tests.utils.asserts import should_NOT_receive
from mdts.tests.utils.asserts import within_sec
from mdts.tests.utils.utils import bindings
from mdts.tests.utils.utils import wait_on_futures

import logging
import time

LOG = logging.getLogger(__name__)
PTM = PhysicalTopologyManager(
    '../topologies/mmm_physical_test_zoom_connection_loss.yaml')
VTM = VirtualTopologyManager(
    '../topologies/mmm_virtual_test_zoom_connection_loss.yaml')
BM = BindingManager(PTM, VTM)


binding_subnet1 = {
    'description': 'Nodes connected via bridge-000-001, 172.16.1.0/24 subnet',
    'bindings': [
        {'binding':
             {'device_name': 'bridge-000-001', 'port_id': 1,
              'host_id': 1, 'interface_id': 1}},
        {'binding':
             {'device_name': 'bridge-000-001', 'port_id': 2,
              'host_id': 2, 'interface_id': 1}},
        ]
    }

binding_subnet2 = {
    'description': 'Nodes connected via bridge-000-002, 172.16.2.0/24 subnet',
    'bindings': [
        {'binding':
             {'device_name': 'bridge-000-002', 'port_id': 1,
              'host_id': 1, 'interface_id': 2}},
        {'binding':
             {'device_name': 'bridge-000-002', 'port_id': 2,
              'host_id': 2, 'interface_id': 2}},
        ]
    }

def assert_ping_from_to_succeeds(sender, receiver):
    f1 = async_assert_that(receiver,
                           receives('dst host %s and icmp' % receiver.get_ip(),
                                    within_sec(5)))
    f2 = sender.ping4(receiver)
    return (f1, f2)

def assert_ping_from_to_fails(sender, receiver):
    f1 = async_assert_that(receiver,
                           should_NOT_receive('dst host %s and icmp' % receiver.get_ip(),
                                    within_sec(5)))
    f2 = sender.ping4(receiver)
    return (f1, f2)


def teardown():
    #Make sure hosts are unblocked at end of test
    agents = service.get_all_containers('midolman')
    zoo = service.get_all_containers('zookeeper')
    zoo_ips = []
    for zookeeper in zoo:
        zoo_ips.append(zookeeper.get_ip_address())
    for agent in agents:
        for ip in zoo_ips:
            agent.eject_packet_loss_for_ip(ip)

    time.sleep(2)
    BM.unbind()

@attr(version="v1.2.0", slow=False)
def test_connection_max_30_sec_connection_loss():
    """
    mdts.tests.functional_tests.test_zoom_connection_loss.test_connection_max_30_sec_connection_loss

    - sets up bridge between two hosts
    - checks the bridge by pinging
    - blocks the connections zookeeper<-->midolman
    - removes the bridge and waits for 20 seconds
    - unblocks the connection zookeeper<-->midolman
    - checks if the bridge is gone now
    - creates a different bridge
    - checks if the new bridge works by pinging
    """

    #Check agents are up
    agents = service.get_all_containers('midolman')
    for agent in agents:
        assert agent.get_service_status() == 'up'

    #Setup bridge for the first subnet
    BM.update_binding_data(binding_subnet1)
    BM.bind()

    #Get the interfaces on the hosts belonging to the first subnet
    sender_i1 = BM.get_iface_for_port('bridge-000-001', 1)
    receiver_i1 = BM.get_iface_for_port('bridge-000-001', 2)
    f1, f2 = assert_ping_from_to_succeeds(sender_i1, receiver_i1)
    wait_on_futures([f1, f2])

    #Sabotage connection to zookeeper on the midolman hosts
    zoo = service.get_all_containers('zookeeper')
    zoo_ips = []
    for zookeeper in zoo:
        zoo_ips.append(zookeeper.get_ip_address())
    for agent in agents:
        for ip in zoo_ips:
            agent.inject_packet_loss_for_ip(ip)

    time.sleep(1)

    #Remove the bindings (one of the agents should own these interfaces)
    for agent in agents:
        agent.unbind_port(sender_i1)
        agent.unbind_port(receiver_i1)

    #Wait for a minimum of 20 seconds, and see if the old bridge stays up
    stoptime = int(round(time.time() * 1000)) + 20000
    while (int(round(time.time() * 1000)) < stoptime):
        f3, f4 = assert_ping_from_to_succeeds(sender_i1, receiver_i1)
        wait_on_futures([f3, f4])
        time.sleep(1)

    #End of sabotage
    for agent in agents:
        for ip in zoo_ips:
            agent.eject_packet_loss_for_ip(ip)

    time.sleep(5)

    #You shall not pass! The bridge should be gone now
    f3, f4 = assert_ping_from_to_fails(sender_i1, receiver_i1)
    wait_on_futures([f3, f4])

    #Setup bridge for the second subnet
    BM.unbind()
    BM.update_binding_data(binding_subnet2)
    BM.bind()

    #Check if second subnet is up
    sender_i2 = BM.get_iface_for_port('bridge-000-002', 1)
    receiver_i2 = BM.get_iface_for_port('bridge-000-002', 2)
    f1, f2 = assert_ping_from_to_succeeds(sender_i2, receiver_i2)
    wait_on_futures([f1, f2])
