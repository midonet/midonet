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
from mdts.lib.binding_manager import BindingManager
from mdts.lib.physical_topology_manager import PhysicalTopologyManager
from mdts.lib.virtual_topology_manager import VirtualTopologyManager
from mdts.tests.utils.asserts import async_assert_that
from mdts.tests.utils.asserts import receives
from mdts.tests.utils.asserts import should_NOT_receive
from mdts.tests.utils.asserts import within_sec
from mdts.tests.utils.utils import bindings
from mdts.tests.utils.utils import wait_on_futures
from nose.plugins.attrib import attr
from nose.tools import nottest
import time

PTM = PhysicalTopologyManager('../topologies/mmm_physical_test_chains.yaml')
VTM = VirtualTopologyManager('../topologies/mmm_virtual_test_chains.yaml')
BM = BindingManager(PTM, VTM)


binding_onehost = {
    'description': 'on single MM',
    'bindings': [
        {'binding':
            {'device_name': 'bridge-000-001', 'port_id': 1,
             'host_id': 1, 'interface_id': 1}},
        {'binding':
            {'device_name': 'bridge-000-001', 'port_id': 2,
             'host_id': 1, 'interface_id': 2}},
    ]
}

binding_multihost = {
    'description': 'spanning across multiple MMs',
    'bindings': [
        {'binding':
            {'device_name': 'bridge-000-001', 'port_id': 1,
             'host_id': 1, 'interface_id': 1}},
        {'binding':
            {'device_name': 'bridge-000-001', 'port_id': 2,
             'host_id': 2, 'interface_id': 1}},
    ]
}


@nottest  # disabling for now
@attr(version="v1.2.0")
@bindings(binding_multihost)
def test_filter_ipv6():
    """
    Title: Filter IPv6 packets out on Bridge

    Scenario 1:
    When: there is no filter settings
    Then: IPv6 packets go through the bridge

    Scenario 2:
    When: the bridge has a chain in which there is a drop rule for IPv6
    Then: IPv6 packets should not go through the bridge

    Scenario 3:
    When: the chain is removed from the bridge
    Then: IPv6 packets should go through again.
    """

    iface1 = BM.get_iface_for_port('bridge-000-001', 1)
    iface2 = BM.get_iface_for_port('bridge-000-001', 2)

    iface1_hw_addr = iface1.interface['hw_addr']
    iface2_hw_addr = iface2.interface['hw_addr']

    ipv6_proto = "86:dd"
    ipv6_icmp = ("60:00:00:00:00:20:3a:ff:fe:80:00:00:00:00:00:00:1a:03:73:ff:"
                 "fe:29:a9:b1:ff:02:00:00:00:00:00:00:00:00:00:01:ff:29:a9:b2:"
                 "87:00:32:26:00:00:00:00:fe:80:00:00:00:00:00:00:1a:03:73:ff:"
                 "fe:29:a9:b2:01:01:18:03:73:29:a9:b1")

    packet = '%s-%s-%s-%s' % (iface2_hw_addr, iface1_hw_addr, ipv6_proto,
                              ipv6_icmp)

    rcv_filter = 'ether dst %s' % iface2_hw_addr

    # Sceneario 1:
    f1 = async_assert_that(iface2, receives(rcv_filter, within_sec(10)))
    # async_assert_that expects only 1 packet. Send only one, because the next
    # tcpdump might capture it (and fail the test) in case it takes some time
    # to arrive.
    # FIXME: make the tcpdump listener configurable
    f2 = iface1.send_ether(packet, count=1)
    wait_on_futures([f1, f2])

    # Scenario 2:
    # setting chain and make sure it's dropped
    chain = VTM.get_chain('drop_ipv6')
    VTM.get_bridge('bridge-000-001').set_inbound_filter(chain)

    f1 = async_assert_that(iface2,
                           should_NOT_receive(
                               rcv_filter, within_sec(10)))
    f2 = iface1.send_ether(packet, count=1)
    wait_on_futures([f1, f2])

    # Remove the filter and verify that packets go through again.
    VTM.get_bridge('bridge-000-001').set_inbound_filter(None)
    time.sleep(1)
    f1 = async_assert_that(iface2, receives(rcv_filter, within_sec(10)))
    f2 = iface1.send_ether(packet, count=1)
    wait_on_futures([f1, f2])


@attr(version="v1.2.0")
@bindings(binding_multihost)
def test_dst_mac_masking():
    """
    Title: Test destination MAC masking in chain rules

    Scenario 1:
    When: There's a rule dropping any traffic with the multicast bit on
    Then: Multicast traffic is blocked and unicast traffic goes through

    Scenario 2:
    When: There's a rule dropping any traffic with the multicast bit off
    Then: Multicast traffic goes through and unicast traffic is blocked
    """

    bridge = VTM.get_bridge('bridge-000-001')

    if1 = BM.get_iface_for_port('bridge-000-001', 1)
    if2 = BM.get_iface_for_port('bridge-000-001', 2)

    if1_hw_addr = if1.get_mac_addr()  # interface['hw_addr']
    if2_hw_addr = if2.get_mac_addr()  # interface['hw_addr']

    if2_ip_addr = if2.get_ip()

    rcv_filter = 'udp and ether src %s' % if1_hw_addr

    bridge.set_inbound_filter(VTM.get_chain('drop_multicast'))
    # Send a frame to an arbitrary multicast address. Bridge doesn't
    # recognize it and will try to flood it to the other port, but the
    # masked MAC rule should drop it since it has the multicast bit set.
    f1 = async_assert_that(if2, should_NOT_receive(rcv_filter, within_sec(10)))
    f2 = if1.send_udp("01:23:45:67:89:ab", if2_ip_addr)
    wait_on_futures([f1, f2])

    # If2's actual MAC address should work, since it doesn't have the bit set.
    f1 = async_assert_that(if2, receives(rcv_filter, within_sec(10)))
    f2 = if1.send_udp(if2_hw_addr, if2_ip_addr)
    wait_on_futures([f1, f2])

    # Change to the chain that allows only multicast addresses.
    bridge.set_inbound_filter(VTM.get_chain('allow_only_multicast'))

    # Send another frame to the multicast address. Bridge doesn't
    # recognize it and will try to flood it to the other port. This
    # time the rule should allow it through.
    f1 = async_assert_that(if2, receives(rcv_filter, within_sec(10)))
    f2 = if1.send_udp("01:23:45:67:89:ab", if2_ip_addr)
    wait_on_futures([f1, f2])

    # If2's actual MAC address should be blocked, since it doesn't
    # have the multicast bit set.
    f1 = async_assert_that(if2, should_NOT_receive(rcv_filter, within_sec(10)))
    f2 = if1.send_udp(if2_hw_addr, if2_ip_addr)
    wait_on_futures([f1, f2])


@attr(version="v1.2.0")
@bindings(binding_onehost)
def test_src_mac_masking():
    """
    Title: Test source MAC masking in chain rules

    Scenario 1:
    When: There's a rule dropping any traffic with an even source MAC
    Then: Traffic from if2 to if1 is blocked because if2's MAC ends with 2
    And:  Traffic from if1 to if2 goes through because if1's MAC ends with 1

    FIXME: moving to the new bindings mechanisms should allow removing
    this restriction.
    Only running this with the one-host binding, because:
    1. The multi-host binding breaks the assumptions that if1 will have
       an odd MAC address and if2 an even one.
    2. This is basically just a sanity test to make sure dl_src_mask is
       wired up. Unit tests and test_dst_mac_masking provide enough
       coverage of the other aspects.
    3. These tests are slow enough as it is.
    """

    bridge = VTM.get_bridge('bridge-000-001')

    if1 = BM.get_iface_for_port('bridge-000-001', 1)
    if2 = BM.get_iface_for_port('bridge-000-001', 2)

    if1_hw_addr = if1.interface['hw_addr']
    if2_hw_addr = if2.interface['hw_addr']

    if1_ip_addr = if1.get_ip()
    if2_ip_addr = if2.get_ip()

    if1_rcv_filter = 'udp and ether dst %s' % if1_hw_addr
    if2_rcv_filter = 'udp and ether dst %s' % if2_hw_addr

    bridge.set_inbound_filter(VTM.get_chain('drop_even_src_mac'))

    # If2 has an even MAC (ends with 2), so traffic from if2 to if1
    # should be dropped.
    f1 = async_assert_that(if1, should_NOT_receive(if1_rcv_filter, within_sec(5)))
    time.sleep(1)
    f2 = if2.send_udp(if1_hw_addr, if1_ip_addr, 41)
    wait_on_futures([f1, f2])

    # If1 has an odd MAC (ends with 1), so traffic from if1 to if2
    # should go through.
    f1 = async_assert_that(if2, receives(if2_rcv_filter, within_sec(5)))
    time.sleep(1)
    f2 = if1.send_udp(if2_hw_addr, if2_ip_addr, 41)
    wait_on_futures([f1, f2])
