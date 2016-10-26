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

from nose.tools import nottest
from mdts.lib.physical_topology_manager import PhysicalTopologyManager
from mdts.lib.virtual_topology_manager import VirtualTopologyManager
from mdts.lib.binding_manager import BindingManager
from mdts.tests.utils.asserts import *

import logging
import time
from mdts.tests.utils.utils import bindings, wait_on_futures


LOG = logging.getLogger(__name__)

PTM = PhysicalTopologyManager('../topologies/mmm_physical_test_delete_port.yaml')
VTM = VirtualTopologyManager('../topologies/mmm_virtual_test_delete_port.yaml')
BM = BindingManager(PTM, VTM)

binding_multihost = {
    'description': 'spanning across multiple MMs',
    'bindings': [
        {'binding':
            {'device_name': 'bridge-000-001', 'port_id': 2,
             'host_id': 1, 'interface_id': 1}},
        {'binding':
            {'device_name': 'bridge-000-002', 'port_id': 2,
             'host_id': 2, 'interface_id': 2}},
    ]
}


@bindings(binding_multihost)
def test_ping_delete_port():
    """
    Title: L3 connectivity over bridge and router, then deletes a port
    and verifies that there is no connectivity. Implemented to cover the
    old DeletePortTest.

    Scenario 1:
    When: a VM sends ICMP echo request with ping command to a different subnet
    Then: the receiver VM should receive the ICMP echo packet.
    And: the ping command succeeds
    When: the destination port on the router is deleted
    Then: the receiver VM should NOT receive the ICMP echo packet
    """

    sender = BM.get_iface_for_port('bridge-000-001', 2)
    receiver = BM.get_iface_for_port('bridge-000-002', 2)

    # The receiver VM needs to send some frames so the MN Router learns
    # the VM's mac address. Otherwise this test would fail with binding2
    # because the MidoNet Router forwards the ICMP with the previous mac
    # found in bindings1 in ethernet headers.
    # Issue: https://midobugs.atlassian.net/browse/MN-79
    receiver.ping4(sender)

    f1 = async_assert_that(receiver,
                           receives('dst host 172.16.2.1 and icmp',
                                    within_sec(5)))
    f2 = sender.ping4(receiver)

    wait_on_futures([f1, f2])

    port = VTM.get_device_port('router-000-001', 2)
    port.destroy()

    f1 = async_assert_that(receiver,
                           should_NOT_receive('dst host 172.16.2.1 and icmp',
                                              within_sec(5)))
    f2 = sender.ping4(receiver)

    wait_on_futures([f1, f2])
