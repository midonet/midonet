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

"""
Tests if Midolman agent / interface status update
"""
from nose import with_setup
from nose.plugins.attrib import attr

from mdts.services import service
from mdts.lib.binding_manager import BindingManager
from mdts.lib.physical_topology_manager import PhysicalTopologyManager
from mdts.lib.virtual_topology_manager import VirtualTopologyManager

from mdts.tests.utils.asserts import receives, async_assert_that, should_NOT_receive, within_sec
from mdts.tests.utils.utils import bindings, wait_on_futures

import logging
import time

LOG = logging.getLogger(__name__)
PTM = PhysicalTopologyManager(
    '../topologies/mmm_physical_test_zoom_connection_loss.yaml')
VTM = VirtualTopologyManager(
    '../topologies/mmm_virtual_test_zoom_connection_loss.yaml')
BM = BindingManager(PTM, VTM)


binding_simple_subnet = {
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

def assert_ping_succeeds(sender, receiver):
    f1 = async_assert_that(receiver,
                           receives('dst host %s and icmp' % receiver.get_ip(),
                                    within_sec(5)))
    f2 = sender.ping4(receiver)
    wait_on_futures([f1, f2])

def assert_ping_fails(sender, receiver):
    f1 = async_assert_that(receiver,
                           should_NOT_receive('dst host %s and icmp' % receiver.get_ip(),
                                    within_sec(5)))
    f2 = sender.ping4(receiver)
    wait_on_futures([f1, f2])


def get_all_service_ips(service_name):
    containers = service.get_all_containers(service_name)
    ips = []
    for container in containers:
        ips.append(container.get_ip_address())
    return ips

def restore_services():
    #Make sure hosts are unblocked at end of test
    agents = service.get_all_containers('midolman')
    zoo_ips = get_all_service_ips('zookeeper')
    for agent in agents:
        for ip in zoo_ips:
            agent.eject_packet_loss_for_ip(ip)

    time.sleep(2)

@attr(version="v1.2.0", slow=False)
@with_setup(None, restore_services)
@bindings(binding_simple_subnet)
def test_connection_max_30_sec_connection_loss():
    """
    Test if agents tolerate a zookeeper connection loss of up to 30 seconds

    - sets up bridge between two hosts
    - checks the bridge by pinging
    - blocks the connections zookeeper<-->midolman
    - disable the bridge and waits for 20 seconds
    - unblocks the connection zookeeper<-->midolman
    - checks if the bridge is down now
    - enable the bridge again
    - checks if the bridge is up again
    """

    #Get the interfaces on the hosts
    sender = BM.get_iface_for_port('bridge-000-001', 1)
    receiver = BM.get_iface_for_port('bridge-000-001', 2)
    assert_ping_succeeds(sender, receiver)

    #Sabotage connection to zookeeper on the midolman hosts
    agents = service.get_all_containers('midolman')
    zoo_ips = get_all_service_ips('zookeeper')
    for agent in agents:
        for ip in zoo_ips:
            agent.inject_packet_loss_for_ip(ip)

    time.sleep(1)

    #Disable the bridge
    bridge = VTM.get_bridge('bridge-000-001')
    bridge.disable()

    #Wait for a minimum of 20 seconds, and see if the old bridge stays up
    stoptime = int(round(time.time() * 1000)) + 20000
    while (int(round(time.time() * 1000)) < stoptime):
        assert_ping_succeeds(sender, receiver)
        time.sleep(1)

    #End of sabotage
    for agent in agents:
        for ip in zoo_ips:
            agent.eject_packet_loss_for_ip(ip)

    time.sleep(5)

    #You shall not pass! The bridge should be disabled now
    assert_ping_fails(sender, receiver)

    #Check if we can enable the bridge again
    bridge.enable()
    time.sleep(5)
    assert_ping_succeeds(sender, receiver)
