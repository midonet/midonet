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

from mdts.lib.physical_topology_manager import PhysicalTopologyManager
from mdts.lib.virtual_topology_manager import VirtualTopologyManager
from mdts.lib.binding_manager import BindingManager
from mdts.lib.failure.netif_failure import NetifFailure
from mdts.lib.failure.no_failure import NoFailure
from mdts.lib.failure.namespaces import *
from mdts.tests.utils.asserts import *
from mdts.tests.utils import *
from nose.tools import with_setup
from nose.plugins.attrib import attr

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
    'description': 'on single MM',
    'bindings': [
        {'binding':
             {'device_name': 'bridge-000-001', 'port_id': 1,
              'host_id': 1, 'interface_id': 1}},
        {'binding':
             {'device_name': 'bridge-000-001', 'port_id': 2,
              'host_id': 1, 'interface_id': 2}},
        {'binding':
             {'device_name': 'bridge-000-001', 'port_id': 3,
              'host_id': 1, 'interface_id': 3}}
        ]
    }


bindings2 = {
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
              'host_id': 2, 'interface_id': 5}}
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

def setup():
    PTM.build()
    VTM.build()


def teardown():
    time.sleep(2)
    PTM.destroy()
    VTM.destroy()


@attr(version="v1.2.0", slow=False)
@failures(NoFailure())
@bindings(bindings1, bindings2)
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

    hw_addr = iface_with_the_hw_addr.interface['hw_addr']
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

    # only iface_with_the_hw_addr should receives the ehternet unicast
    f1 = async_assert_that(iface_with_the_hw_addr,
                           receives(match_on_the_hw_addr, within_sec(5)))
    f2 = async_assert_that(iface_x,
                           should_NOT_receive(match_on_the_hw_addr,
                                              within_sec(5)))
    sender.send_ether(ethernet_unicast_to_the_hw_addr, count=3)
    wait_on_futures([f1, f2])


@attr(version="v1.2.0", slow=False)
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

    # Check that interface has 1500 byte MTU before DHCP
    assert iface.get_mtu() == "1500"

    # Check that namespace doesn't have routes before DHCP
    assert iface.get_num_routes() == 0

    # Run dhclient in the namespace for a while
    try:
        with open('/etc/issue', 'r') as f:
            issue = f.read()

        if str.find(issue, "Ubuntu") >= 0:
            iface.execute('dhclient -v -d --no-pid -lf /var/lib/dhcp/dhclient.leases.mdts $peer_if 2>/dev/null',
                          timeout=15, sync=True)
        elif str.find(issue, "Red Hat Enterprise Linux") >= 0:
            iface.execute('/sbin/dhclient -v -d -sf /sbin/dhclient-script -R subnet-mask,broadcast-address,interface-mtu $peer_if 2>/dev/null',
                          timeout=15, sync=True)
        else:
            iface.execute('/sbin/dhclient -v -d -sf dhclient-script -R subnet-mask,broadcast-address,interface-mtu $peer_if 2>/dev/null',
                          timeout=15, sync=True)
    except subprocess.CalledProcessError as e:
        # we just want to let dhclient go
        pass

    # Assert that the interface gets ip address
    assert iface.get_cidr() == '172.16.1.101/24'

    #TODO(tomoe): assert for default gw and static routes with opt 121
    assert iface.get_num_routes() > 0

    # MTU should be 1450 (interface mtu minus 50B, the max of gre/vxlan overhead)
    assert iface.get_mtu() == "1450"


@attr(version="v1.2.0", slow=False)
@failures(NoFailure(),
          NetifFailure(NS_ZOOKEEPER_1, 'eth0', 30),
          NetifFailure(NS_ZOOKEEPER_2, 'eth0', 30),
          NetifFailure(NS_ZOOKEEPER_3, 'eth0', 30))
@bindings(bindings1, bindings2)
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

    f1 = sender.ping4(receiver)

    assert_that(receiver, receives('dst host 172.16.1.3 and icmp',
                                 within_sec(5)))
    wait_on_futures([f1])


@attr(version="v1.2.0", slow=False)
@bindings(bindings1, bindings2)
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

    f1 = sender.ping4(receiver, 0.5, 3, False, 2000)

    assert_that(receiver, receives('dst host 172.16.1.3 and icmp',
                                   within_sec(5)))
    wait_on_futures([f1])


@attr(version="v1.2.0", slow=False)
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

    f1 = sender.ping4(receiver, 0.5, 3, False, 100, suppress_failure=True)

    assert_that(receiver, should_NOT_receive('', within_sec(5)))
    wait_on_futures([f1])


# Deprecate MN-662
@attr(version="v1.2.0", slow=False)
@bindings(bindings1, bindings2)
def test_flow_invalidation_on_mac_update():
    """
    Title: Flow invalidation, learning MACs

    The bridge learns the MACs from the traffic flowing by its ports.
    When the bridge learns a MAC has 'moved' to another port, it should
    send traffic only to that port.
    """

    # First: packets go from sender to receiver
    sender = BM.get_iface_for_port('bridge-000-001', 1)
    receiver = BM.get_iface_for_port('bridge-000-001', 2)
    intruder = BM.get_iface_for_port('bridge-000-001', 3)

    f1 = async_assert_that(receiver,
                           receives('icmp', within_sec(5)))

    f2 = async_assert_that(intruder,
                           should_NOT_receive('icmp', within_sec(5)))
    time.sleep(1)
    f3 = sender.ping4(receiver, do_arp=True)
    wait_on_futures([f1, f2, f3])

    # Second: intruder claims to be receiver
    receiver_MAC = receiver.interface['hw_addr']
    frame = '%s-%s-aa:bb' % (receiver_MAC, receiver_MAC)
    intruder.send_ether(frame)

    # Third: packets go from sender to intruder
    f1 = async_assert_that(receiver,
                           should_NOT_receive('icmp', within_sec(5)))

    f2 = async_assert_that(intruder,
                           receives('icmp', within_sec(5)))
    time.sleep(1)
    # Do NOT send ARP request: broadcasting it makes receiver to
    # generate ARP reply and updates MAC learning table
    f3 = sender.ping4(receiver, suppress_failure=True, do_arp=False)
    wait_on_futures([f1, f2, f3])


@attr(version="v1.2.0", slow=False)
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

    f1 = sender.ping4(receiver)

    f2 = async_assert_that(receiver,
                           receives('dst host 172.16.1.3 and icmp',
                                    within_sec(5)))
    wait_on_futures([f1, f2])

    receiver.set_down()

    f1 = sender.ping4(receiver, suppress_failure=True)

    f2 = async_assert_that(receiver, should_NOT_receive('icmp', within_sec(5)))

    wait_on_futures([f1, f2])

    receiver.set_up()

    f1 = sender.ping4(receiver)

    f2 = async_assert_that(receiver,
                           receives('dst host 172.16.1.3 and icmp',
                                    within_sec(5)))
    wait_on_futures([f1, f2])


def teardown_tst_rule_changes():
    VTM.get_device_port('bridge-000-001', 2).set_outbound_filter(None)


@attr(version="v1.2.0", slow=False)
@bindings(bindings1, bindings2)
@with_setup(lambda: None, teardown_tst_rule_changes)
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
    time.sleep(1)
    f2 = sender.ping4(receiver, do_arp=True)
    wait_on_futures([f1, f2])

    # Add a filter dropping all IPv4 traffic to port 2.
    chain = VTM.get_chain('drop_ipv4')
    VTM.get_device_port('bridge-000-001', 2).set_outbound_filter(chain)

    # The second ping should not reach port 2.
    f3 = async_assert_that(receiver,
                           should_NOT_receive('icmp', within_sec(5)))
    time.sleep(1)
    f4 = sender.ping4(receiver, do_arp=True, suppress_failure=True)
    wait_on_futures([f3, f4])

    # After removing the filter, ping should succeed again.
    VTM.get_device_port('bridge-000-001', 2).set_outbound_filter(None)
    f1 = async_assert_that(receiver,
                           receives('icmp', within_sec(5)))
    time.sleep(1)
    f2 = sender.ping4(receiver, do_arp=True)
    wait_on_futures([f1, f2])
