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
from mdts.lib.failure.service_failure import ServiceFailure

from mdts.lib.physical_topology_manager import PhysicalTopologyManager
from mdts.lib.virtual_topology_manager import VirtualTopologyManager
from mdts.lib.binding_manager import BindingManager
from mdts.lib.failure.no_failure import NoFailure
from mdts.tests.utils.asserts import *
from nose.plugins.attrib import attr
from mdts.tests.utils.utils import bindings
from mdts.tests.utils.utils import failures
from mdts.tests.utils.utils import wait_on_futures

from hamcrest import *

import logging
import time
import os
import pdb
import re
import subprocess

LOG = logging.getLogger(__name__)

PTM = PhysicalTopologyManager('../topologies/mmm_physical_test_bridge.yaml')
VTM = VirtualTopologyManager('../topologies/mmm_virtual_test_bridge.yaml')
BM = BindingManager(PTM, VTM)

bindings1 = {
    'description': 'spanning across two MM',
    'bindings': [
        {'binding':
            {'device_name': 'bridge-000-001', 'port_id': 1,
             'host_id': 1, 'interface_id': 1}},
        {'binding':
            {'device_name': 'bridge-000-001', 'port_id': 2,
             'host_id': 2, 'interface_id': 2}},
        {'binding':
            {'device_name': 'bridge-000-001', 'port_id': 3,
             'host_id': 2, 'interface_id': 3}},
    ]
}

binding_dhcp = {
    'description': 'binding for dhcp test',
    'bindings': [
        {'binding':
            {'device_name': 'bridge-000-001', 'port_id': 2,
             'host_id': 2, 'interface_id': 5}},
        {'binding':
            {'device_name': 'bridge-000-001', 'port_id': 3,
             'host_id': 1, 'interface_id': 5}}
    ]
}

binding_two_isolated_bridges = {
    'description': 'two isolated bridges',
    'bindings': [
        {'binding':
            {'device_name': 'bridge-000-001', 'port_id': 1,
             'host_id': 1, 'interface_id': 2}},
        {'binding':
            {'device_name': 'bridge-000-002', 'port_id': 1,
             'host_id': 2, 'interface_id': 2}}
    ]
}


@attr(version="v1.2.0")
@failures(NoFailure())
@bindings(bindings1)
def test_mac_learning():
    """
    Title: Bridge mac learning

    Scenario 1:
    When: the destination ethernet address has never been seen before.
    Then: the bridge should flood the ethernet unicast

    Scenario 2:
    When: the destination ethernet address has been seen before.
    Then: the bridge should not flood the ethernet frame, instaed it should
          forward to only the port that is connected to the interface with
          the mac address.
    """
    sender = BM.get_iface_for_port('bridge-000-001', 1)
    iface_with_the_hw_addr = BM.get_iface_for_port('bridge-000-001', 2)
    iface_x = BM.get_iface_for_port('bridge-000-001', 3)

    hw_addr = iface_with_the_hw_addr.get_mac_addr()
    match_on_the_hw_addr = 'ether dst ' + hw_addr

    ethernet_unicast_to_the_hw_addr = '%s-7e:1f:ff:ff:ff:ff-aa:bb' % (hw_addr)

    # Scenario 1:
    # Both interfaces should get the frname as the bridge should flood it.

    f1 = async_assert_that(iface_with_the_hw_addr,
                           receives(match_on_the_hw_addr, within_sec(5)))
    f2 = async_assert_that(iface_x,
                           receives(match_on_the_hw_addr, within_sec(5)))
    time.sleep(1)

    sender.send_ether(ethernet_unicast_to_the_hw_addr, count=3)
    wait_on_futures([f1, f2])

    # Scenario 2:

    # Get the bridge to learn the mac address
    iface_with_the_hw_addr.ping4(sender, sync=True)

    time.sleep(1)

    # only iface_with_the_hw_addr should receives the ehternet unicast
    f1 = async_assert_that(iface_with_the_hw_addr,
                           receives(match_on_the_hw_addr, within_sec(5)))
    f2 = async_assert_that(iface_x,
                           should_NOT_receive(match_on_the_hw_addr,
                                              within_sec(5)))
    sender.send_ether(ethernet_unicast_to_the_hw_addr, count=1)
    wait_on_futures([f1, f2])


@attr(version="v1.2.0")
@bindings(binding_dhcp)
def test_dhcp():
    """
    Title: DHCP feature in MidoNet Bridge

    Scenario 1:
    Given: a bridge that has DHCP configurations
    When: a VM connected to the bridge sends DHCP requests,
    Then: the VM should get DHCP response accoridingly.
    """
    iface = BM.get_iface_for_port('bridge-000-001', 2)
    iface_new = BM.get_iface_for_port('bridge-000-001', 3)
    shared_lease = '/override/shared-%s.lease' % iface.get_ifname()
    try:

        # Check that interface has 1500 byte MTU before DHCP
        assert iface.get_mtu(update=True) == 1500

        # Check that namespace doesn't have routes before DHCP
        assert iface.get_num_routes(update=True) == 0

        # Run dhclient in the namespace for a while with a specific lease file
        # FIXME: wait 15 seconds? better to wait for an ack from the command?
        result = iface.execute(
            'dhclient -lf %s %s' % (shared_lease, iface.get_ifname()),
            timeout=15, sync=True)
        LOG.debug('dhclient got response: %s' % result)

        # Assert that the interface gets ip address
        assert_that(iface.get_cidr(update=True), equal_to('172.16.1.101/24'),
                    "Wrong CIDR")

        # TODO(tomoe): assert for default gw and static routes with opt 121
        assert_that(iface.get_num_routes(update=True), greater_than(0),
                    "No routes found")

        # MTU should be 1450 (interface mtu minus 50B, max of gre/vxlan overhead)
        assert_that(iface.get_mtu(update=True), equal_to(1450),
                    "Wrong MTU")

        # MI-536 regression test
        # Check that the 2nd vm using an incorrect lease file, receives a nack
        # without waiting for the request to timeout which is 60s.
        iface_new.update_interface_name(iface.get_ifname())
        result = iface_new.execute(
            'dhclient -lf %s %s' % (shared_lease, iface_new.get_ifname()),
            timeout=15, sync=True)
        LOG.debug('dhclient got response: %s' % result)

        # After 15s, check if the interface is correctly configured
        assert iface_new.get_cidr(update=True) == '172.16.1.100/24'
        assert iface_new.get_num_routes(update=True) > 0
        assert iface_new.get_mtu(update=True) == 1450
    finally:
        # Cleanup lease file
        iface.execute('rm -rf /override/shared-%s.lease' % iface.get_ifname())

@attr(version="v1.2.0")
@failures(NoFailure(),
          ServiceFailure('zookeeper1'),
          ServiceFailure('zookeeper2'),
          ServiceFailure('zookeeper3'))
@bindings(bindings1)
def test_icmp():
    """
    Title: ICMP reachability over bridge

    Scenario 1:
    When: a VM sends ICMP echo request with ping command
    Then: the receiver VM should receive the ICMP echo packet.
    And: the ping command succeeds
    """
    sender = BM.get_iface_for_port('bridge-000-001', 1)
    receiver = BM.get_iface_for_port('bridge-000-001', 3)

    f1 = async_assert_that(receiver,
                           receives('dst host %s and icmp' % receiver.get_ip(),
                                    within_sec(5)))
    f2 = sender.ping4(receiver)

    wait_on_futures([f1, f2])


@attr(version="v1.2.0")
@bindings(bindings1)
def test_fragmented_packets():
    """
    Title: Fragmented IP packets through a bridge

    Scenario 1:
    When: a VM sends large PING packets (> MTU)
    Then: the receiver should receive the ICMP echo packet
    And: the ping command succeeds
    """

    sender = BM.get_iface_for_port('bridge-000-001', 1)
    receiver = BM.get_iface_for_port('bridge-000-001', 3)

    f2 = async_assert_that(receiver,
                           receives('dst host 172.16.1.3 and icmp',
                                    within_sec(5)))
    f1 = sender.ping4(receiver, 0.5, 3, False, 2000)

    wait_on_futures([f1, f2])


@attr(version="v1.2.0")
@bindings(binding_two_isolated_bridges)
def test_two_isolated_bridges():
    """
    Title: Two isolated bridges

    All traffic between two VMs in different and
    unconnected bridges should be independent, so
    receiver shouldn't get any packets
    """

    sender = BM.get_iface_for_port('bridge-000-001', 1)
    receiver = BM.get_iface_for_port('bridge-000-002', 1)

    f2 = async_assert_that(receiver,
                           should_NOT_receive('', within_sec(5)))

    f1 = sender.ping4(receiver, 0.5, 3, False, 100)

    wait_on_futures([f1, f2])


# Deprecate MN-662
@attr(version="v1.2.0")
@bindings(bindings1)
def test_flow_invalidation_on_mac_update():
    """
    Title: Flow invalidation, learning MACs

    The bridge learns the MACs from the traffic flowing by its ports.
    When the bridge learns a MAC that has 'moved' to another port, it should
    send traffic only to that port.
    """

    sender = BM.get_iface_for_port('bridge-000-001', 1)
    receiver = BM.get_iface_for_port('bridge-000-001', 2)
    intruder = BM.get_iface_for_port('bridge-000-001', 3)

    receiver_MAC = receiver.get_mac_addr()
    frame = '%s-%s-aa:bb' % (receiver_MAC, receiver_MAC)

    capture = 'icmp and src host %s' % (sender.get_ip())

    # Populate ARP table
    sender.execute('arp -s %s %s' % (receiver.get_ip(), receiver_MAC))
    receiver.execute('arp -s %s %s' % (sender.get_ip(), sender.get_mac_addr()))

    # Trigger receiver MAC learning
    receiver.send_ether(frame)

    # First: packets go from sender to receiver
    f1 = async_assert_that(receiver, receives(capture, within_sec(5)))
    f2 = async_assert_that(intruder, should_NOT_receive(capture, within_sec(5)))
    f3 = sender.ping4(receiver)
    wait_on_futures([f1, f2, f3])

    # Second: intruder claims to be receiver
    intruder.send_ether(frame)

    # Third: packets go from sender to intruder
    f1 = async_assert_that(receiver, should_NOT_receive(capture, within_sec(5)))
    f2 = async_assert_that(intruder, receives(capture, within_sec(5)))
    f3 = sender.ping4(receiver)
    wait_on_futures([f1, f2, f3])


@attr(version="v1.2.0")
@bindings(bindings1)
def test_icmp_after_interface_recovery():
    """
    Title: ICMP reachability over bridge before and after interfaces go
    down

    Scenario 1:
    When: a VM sends ICMP echo request with ping command
    Then: the receiver VM should receive the ICMP echo packet.
    And: the ping command succeeds
    Then: the receiver VM's tap goes down
    And: the ping command fails
    Then: the receiver VM's tap goes back up
    And: the ping command succeeds
    """

    sender = BM.get_iface_for_port('bridge-000-001', 1)
    receiver = BM.get_iface_for_port('bridge-000-001', 3)

    f1 = async_assert_that(receiver,
                           receives('dst host 172.16.1.3 and icmp',
                                    within_sec(5)))
    f2 = sender.ping4(receiver)
    wait_on_futures([f1, f2])

    receiver.set_down()

    f1 = async_assert_that(receiver,
                           should_NOT_receive('icmp',
                                              within_sec(5),
                                              on_host_interface(True)))
    f2 = sender.ping4(receiver)

    wait_on_futures([f1, f2])

    receiver.set_up()

    f1 = async_assert_that(receiver,
                           receives('dst host 172.16.1.3 and icmp',
                                    within_sec(5)))
    f2 = sender.ping4(receiver)
    wait_on_futures([f1, f2])


@attr(version="v1.2.0")
@bindings(bindings1)
def test_rule_changes():
    """
    Title: ICMP reachability over bridge before and after adding rule
    to drop IPv4 traffic.

    Scenario 1:
    When: A VM sends ICMP echo request with ping command
    Then: The receiver VM should receive the ICMP echo packet.
    And: The ping command succeeds
    Then: The receiver adds a rule blocking IPv4 traffic.
    And: The ping command fails
    Then: the receiver removes the rule
    And: The ping succeeds again.
    """

    sender = BM.get_iface_for_port('bridge-000-001', 1)
    receiver = BM.get_iface_for_port('bridge-000-001', 2)

    # There are no filters, so the first ping should succeed.
    f1 = async_assert_that(receiver,
                           receives('icmp', within_sec(5)))
    f2 = sender.ping4(receiver, do_arp=True)
    wait_on_futures([f1, f2])

    # Add a filter dropping all IPv4 traffic to port 2.
    chain = VTM.get_chain('drop_ipv4')
    VTM.get_device_port('bridge-000-001', 2).set_outbound_filter(chain)

    # The second ping should not reach port 2.
    f1 = async_assert_that(receiver,
                           should_NOT_receive('icmp', within_sec(5)))
    f2 = sender.ping4(receiver, do_arp=True)
    wait_on_futures([f1, f2])

    # After removing the filter, ping should succeed again.
    VTM.get_device_port('bridge-000-001', 2).set_outbound_filter(None)
    f1 = async_assert_that(receiver,
                           receives('icmp', within_sec(5)))
    f2 = sender.ping4(receiver, do_arp=True)
    wait_on_futures([f1, f2])
