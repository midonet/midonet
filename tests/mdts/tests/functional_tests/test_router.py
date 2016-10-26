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

from nose.plugins.attrib import attr
from mdts.lib.physical_topology_manager import PhysicalTopologyManager
from mdts.lib.virtual_topology_manager import VirtualTopologyManager
from mdts.lib.binding_manager import BindingManager
from mdts.tests.utils.asserts import *
from mdts.tests.utils.utils import bindings
from mdts.tests.utils.utils import wait_on_futures

from hamcrest import *
from nose.tools import nottest

import logging
import time
import pdb
import re
import subprocess


LOG = logging.getLogger(__name__)

PTM = PhysicalTopologyManager('../topologies/mmm_physical_test_router.yaml')
VTM = VirtualTopologyManager('../topologies/mmm_virtual_test_router.yaml')
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


@attr(version="v1.2.0")
@bindings(binding_multihost)
def test_ping_different_subnets():
    """
    Title: L3 connectivity over bridge and router
           (MN-L3-ICMP-2)

    Scenario 1:
    When: a VM sends ICMP echo request with ping command to a different subnet
    Then: the receiver VM should receive the ICMP echo packet.
    And: the ping command succeeds
    """

    sender = BM.get_iface_for_port('bridge-000-001', 2)
    receiver = BM.get_iface_for_port('bridge-000-002', 2)

    # The receiver VM needs to send some frames so the MN Router learns
    # the VM's mac address. Otherwise this test would fail with binding2
    # because the MidoNet Router forwards the ICMP with the previous mac
    # found in bindings1 in ethernet headers.
    # Issue: https://midobugs.atlassian.net/browse/MN-79
    # FIXME: do_arp not necessary if random macs are used
    receiver.ping4(sender, sync=True, do_arp=True)

    f1 = async_assert_that(receiver,
                           receives('dst host 172.16.2.1 and icmp',
                                    within_sec(5)))
    f2 = sender.ping4(receiver)

    wait_on_futures([f1, f2])


@attr(version="v1.2.0")
@bindings(binding_multihost)
def test_fragmented_packets():
    """
    Title: L3 connectivity over bridge and router
           (MN-L3-ICMP-2) with large packets that will be fragmented

    Scenario 1:
    When: a VM sends a ICMP echo request with size >MTU uwing ping command
          to a different subnet
    Then: the receiver VM should receive the ICMP echo packet.
    And: the ping command succeeds
    """

    sender = BM.get_iface_for_port('bridge-000-001', 2)
    receiver = BM.get_iface_for_port('bridge-000-002', 2)

    # The receiver VM needs to send some frames so the MN Router learns
    # the VM's mac address. Otherwise this test would fail with binding2
    # because the MidoNet Router forwards the ICMP with the previous mac
    # found in bindings1 in ethernet headers.
    # Issue: https://midobugs.atlassian.net/browse/MN-79
    receiver.ping4(sender, 0.5, 3, True, 2000, do_arp=True)

    f1 = async_assert_that(receiver,
                           receives('dst host 172.16.2.1 and icmp', within_sec(5)))

    f2 = sender.ping4(receiver, 0.5, 3, False, 2000)

    wait_on_futures([f1, f2])


@bindings(binding_multihost)
def test_spoofed_arp_reply():
    """
    Title: Test spoofed ARP reply

    Given: Sender is bridge-000-001, port 2, at 176.16.1.1
           Receiver is bridge-000-002, port 2, at 176.16.2.1

    Scenario 1:
    When: Sender pings 176.16.2.2
    Then: The ping fails.

    Scenario 2:
    When: Receiver sends an unsolicited ARP reply to its gateway (172.16.2.254)
          with source IP 176.16.2.2 and its own MAC address as source MAC.
    Then: The router maps 176.16.2.2 to receiver's MAC address.
    And:  Sender pings 176.16.2.2
    Then: The ping succeeds.

    Scenario 3:
    When: Receiver sends an unsolicited ARP reply to its gateway (172.16.2.254)
          with source IP 176.16.1.2 and its own MAC address as source MAC.
    Then: The router ignores it because 176.16.1.2 is not in the subnet
          (172.16.2.254/24) of the port through which the ARP reply came in.
    And:  Sender pings 176.16.1.2
    Then: The ping fails.
    """
    sender = BM.get_iface_for_port('bridge-000-001', 2)
    receiver = BM.get_iface_for_port('bridge-000-002', 2)

    # 176.16.2.2 is not in the router's ARP table. Ping fails.
    f1 = async_assert_that(receiver,
                           should_NOT_receive('dst host 172.16.2.2 and icmp',
                                              within_sec(5)))
    sender.ping_ipv4_addr('172.16.2.2')
    wait_on_futures([f1])

    # Sender sends an unsolicited ARP reply with source IP 172.16.2.2,
    # which the router maps to sender's MAC address.
    router_port = VTM.get_router('router-000-001').get_port(2)
    router_mac = router_port.get_mn_resource().get_port_mac()

    receiver.send_arp_reply(receiver.get_mac_addr(), router_mac,
                            '172.16.2.2', '172.16.2.254')

    # wait for the arp reply effect to be propagated
    time.sleep(20)

    # Ping now succeeds.
    f1 = async_assert_that(receiver,
                           receives('dst host 172.16.2.2 and icmp',
                                    within_sec(5)))
    sender.ping_ipv4_addr('172.16.2.2')
    wait_on_futures([f1])

    # This ARP reply is ignored because 172.16.1.2 is not in the subnet of
    # router port 2, so a ping to 172.16.1.2 is ignored.
    f1 = async_assert_that(receiver,
                           should_NOT_receive('dst host 172.16.1.2 and icmp',
                                              within_sec(5)))
    receiver.send_arp_reply(receiver.get_mac_addr(), router_mac,
                            '172.16.1.2', '172.16.2.254')
    sender.ping_ipv4_addr('172.16.1.2')
    wait_on_futures([f1])


@nottest
@bindings(binding_multihost)
def test_routing_weight():
    """
    NOTE: THIS TEST IS DISABLED DUE TO MN-268

    Title: Routing with weight property
           (MN-L3-RT-1)

    Scenario 1:
    Given: two route entries that have common dest address with
           different weights and next hop:

           route: dest 172.16.0.0/24 to port_a with weight 10
           route: dest 172.16.0.0/24 to port_b with weight 20

    When: a VM sends IP packet to the subnet specified in the routes(172.16.0.2)
    Then: the packet should be routed according to the route with smaller
          weight(port_a).
    """
    pass


@nottest
@bindings(binding_multihost)
def test_routing_prefixlen():
    """
    NOTE: THIS TEST IS DISABLED DUE TO MN-268

    Title: Routing with different prefix lengths
           (MN-L3-RT-3)


    Scenario 1:
    Given: two routes that has same dest address with different subnet length

           route:dest 172.16.0.0/16 to port_a with weight 10
           route:dest 172.16.0.0/24 to port_b with weight 10

    When: a VM sends IP packet to the subnet specified in the routes(172.16.0.2)
    Then: the packet should be routed according to the route with more specific
          routes or longer subnet length(port_b)
    """
    pass


@nottest
@bindings(binding_multihost)
def test_routing_prefixlen_weight():
    """
    NOTE: THIS TEST IS DISABLED DUE TO MN-268

    Title: Routing with different prefix lengths and weight
           (MN-L3-RT-2)

    Scenario 1:
    Given: two routes that has similar dest address with different subnet length

           route: dest 172.16.0.0/16 to port_a weight 10
           route: dest 172.16.0.0/24 to port_b weight 20

    When: a VM sends IP packet to the subnet specified in the routes(172.16.0.2)
    Then: the packet should be routed according to the route with more specific
          routes or longer subnet length (port_b)
    """
    pass

@nottest
@bindings(binding_multihost)
def test_routing_balancing():
    """
    NOTE: THIS TEST IS DISABLED DUE TO MN-268

    Originally, this test checks that a router with two identical routes should
    forward flows in a round robin basis on a single midolman(MN-L3-RT-4).
    However, given  the limitation in MidoNet Router (MN-268) where a router
    has only single arp table, testing this doesn't make much sense,
    although we could test by using different IP. We will add this test back
    when MidoNet router supports separate arp tables within a single router.
     """
    pass
