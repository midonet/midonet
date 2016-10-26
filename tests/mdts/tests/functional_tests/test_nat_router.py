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

from mdts.lib.binding_manager import BindingManager
from mdts.lib.physical_topology_manager import PhysicalTopologyManager
from mdts.lib.virtual_topology_manager import VirtualTopologyManager
from mdts.tests.utils.asserts import async_assert_that
from mdts.tests.utils.asserts import receives
from mdts.tests.utils.asserts import receives_icmp_unreachable_for_udp
from mdts.tests.utils.asserts import should_NOT_receive
from mdts.tests.utils.asserts import within_sec
from mdts.tests.utils.utils import bindings
from mdts.tests.utils.utils import wait_on_futures

import logging
import time


LOG = logging.getLogger(__name__)
PTM = PhysicalTopologyManager('../topologies/mmm_physical_test_nat_router.yaml')
VTM = VirtualTopologyManager('../topologies/mmm_virtual_test_nat_router.yaml')
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


def set_filters(router_name, inbound_filter_name, outbound_filter_name):
    """Sets in-/out-bound filters to a router."""
    router = VTM.get_router(router_name)

    inbound_filter = None
    if inbound_filter_name:
        inbound_filter = VTM.get_chain(inbound_filter_name)
    outbound_filter = None
    if outbound_filter_name:
        outbound_filter = VTM.get_chain(outbound_filter_name)

    router.set_inbound_filter(inbound_filter)
    router.set_outbound_filter(outbound_filter)
    # Sleep here to make sure that the settings have been propagated.
    time.sleep(5)


def unset_filters(router_name):
    """Unsets in-/out-bound filters from a router."""
    set_filters(router_name, None, None)


def feed_receiver_mac(receiver):
    """Feeds the receiver's mac address to the MidoNet router."""
    try:
        router_port = VTM.get_router('router-000-001').get_port(2)
        router_ip = router_port.get_mn_resource().get_port_address()
        receiver_ip = receiver.get_ip()
        f1 = async_assert_that(receiver, receives('dst host %s' % receiver_ip,
                                                  within_sec(10)))
        receiver.send_arp_request(router_ip)
        wait_on_futures([f1])
    except:
        LOG.warn('Oops, sending ARP from the receiver VM failed.')


@attr(version="v1.2.0")
@bindings(binding_multihost)
def test_dnat():
    """
    Title: Tests DNAT on ping messages.

    Scenario 1:
    When: a VM sends ICMP echo request with ping command to an unassigned IP
          address.
    Then: the router performs DNAT on the message according to the rule chain
          set to the router,
    And: the receiver VM should receive the ICMP echo packet,
    And: the ping command succeeds
    """
    sender = BM.get_iface_for_port('bridge-000-001', 2)
    receiver = BM.get_iface_for_port('bridge-000-002', 2)

    # Reset in-/out-bound filters.
    unset_filters('router-000-001')
    feed_receiver_mac(receiver)

    f2 = async_assert_that(receiver, should_NOT_receive('dst host 172.16.2.1 and icmp',
                                             within_sec(5)))
    f1 = sender.ping_ipv4_addr('100.100.100.100')
    wait_on_futures([f1, f2])

    # Set DNAT rule chains to the router
    set_filters('router-000-001', 'pre_filter_001', 'post_filter_001')

    f2 = async_assert_that(receiver, receives('dst host 172.16.2.1 and icmp',
                                   within_sec(5)))
    f3 = async_assert_that(sender, receives('src host 100.100.100.100 and icmp',
                                 within_sec(5)))
    f1 = sender.ping_ipv4_addr('100.100.100.100')
    wait_on_futures([f1, f2, f3])


@attr(version="v1.2.0")
@bindings(binding_multihost)
def test_dnat_for_udp():
    """
    Title: Tests DNAT on UDP packets.

    Scenario:
    When: a VM sends UDP packets to an unassigned IP address.
    Then: the router performs DNAT on the message according to the rule chain
          set to the router,
    And: the UDP packets reach the receiver VM.
    And: because the UDP port is not open, the receiver VM returns ICMP error
         responses.
    """
    sender = BM.get_iface_for_port('bridge-000-001', 2)
    receiver = BM.get_iface_for_port('bridge-000-002', 2)

    # Reset in-/out-bound filters.
    unset_filters('router-000-001')
    feed_receiver_mac(receiver)

    # Target hardware is a router's incoming port.
    router_port = VTM.get_router('router-000-001').get_port(1)
    router_mac = router_port.get_mn_resource().get_port_mac()
    f2 = async_assert_that(receiver,
                           should_NOT_receive('dst host 172.16.2.1 and udp',
                                              within_sec(5)))
    f1 = sender.send_udp(router_mac, '100.100.100.100', 29,
                         src_port=9, dst_port=9)
    wait_on_futures([f1, f2])

    # Set DNAT rule chains to the router
    set_filters('router-000-001', 'pre_filter_001', 'post_filter_001')

    f1 = async_assert_that(receiver,
                           receives('dst host 172.16.2.1 and udp',
                                    within_sec(5)))
    # Sender should receive ICMP unreachable as the receiver port is not open.
    f2 = async_assert_that(sender,
                           receives_icmp_unreachable_for_udp(
                               '172.16.1.1', '100.100.100.100',
                               udp_src_port=9, udp_dst_port=9,
                               timeout=within_sec(10)))
    f3 = sender.send_udp(router_mac, '100.100.100.100', 29,
                         src_port=9, dst_port=9)
    wait_on_futures([f1, f2, f3])


@attr(version="v1.2.0")
@bindings(binding_multihost)
def test_snat():
    """
    Title: Tests SNAT on ping messages.

    Scenario:
    When: a VM sends ICMP echo request with ping command to a different subnet,
    Then: the router performs SNAT on the message according to the rule chain
          set to the router,
    And: the receiver VM should receive the ICMP echo packet, with src address
         NATted,
    And: the ping command succeeds.
    """
    sender = BM.get_iface_for_port('bridge-000-001', 2)
    receiver = BM.get_iface_for_port('bridge-000-002', 2)

    # Reset in-/out-bound filters.
    unset_filters('router-000-001')
    feed_receiver_mac(receiver)

    # No SNAT configured. Should not receive SNATed messages.
    f2 = async_assert_that(receiver, should_NOT_receive('src host 172.16.1.100 and icmp',
                                             within_sec(5)))
    f1 = sender.ping4(receiver)
    wait_on_futures([f1, f2])

    # Set SNAT rule chains to the router
    set_filters('router-000-001', 'pre_filter_002', 'post_filter_002')

    # The receiver should receive SNATed messages.
    f2 = async_assert_that(receiver, receives('src host 172.16.1.100 and icmp',
                                   within_sec(5)))
    f3 = async_assert_that(sender, receives('dst host 172.16.1.1 and icmp',
                                 within_sec(5)))
    f1 = sender.ping4(receiver)
    wait_on_futures([f1, f2, f3])


@attr(version="v1.2.0")
@bindings(binding_multihost)
def test_snat_for_udp():
    """
    Title: Tests SNAT on UDP packets.

    Scenario:
    When: a VM sends UDP packets to an unassigned IP address.
    Then: the router performs SNAT on the message according to the rule chain
          set to the router,
    And: the UDP packets reach the receiver VM, with src address NATted,
    And: because the UDP port is not open, the receiver VM returns ICMP error
         responses.
    """
    sender = BM.get_iface_for_port('bridge-000-001', 2)
    receiver = BM.get_iface_for_port('bridge-000-002', 2)

    # Reset in-/out-bound filters.
    unset_filters('router-000-001')
    feed_receiver_mac(receiver)

    # Target hardware is a router's incoming port.
    router_port = VTM.get_router('router-000-001').get_port(1)
    router_mac = router_port.get_mn_resource().get_port_mac()

    # No SNAT configured. Should not receive SNATed messages.
    f2 = async_assert_that(receiver, should_NOT_receive('src host 172.16.1.100 and udp',
                                             within_sec(5)))
    f1 = sender.send_udp(router_mac, '172.16.2.1', 29,
                         src_port=9, dst_port=65000)
    wait_on_futures([f1, f2])

    # Set SNAT rule chains to the router
    set_filters('router-000-001', 'pre_filter_002', 'post_filter_002')

    # The receiver should receive SNATed messages.
    f2 = async_assert_that(receiver, receives('src host 172.16.1.100 and udp',
                                   within_sec(5)))
    # Sender should receive ICMP unreachable as the receiver port is not open.
    f3 = async_assert_that(sender, receives_icmp_unreachable_for_udp(
                                '172.16.1.1', '172.16.2.1',
                                udp_src_port=9, udp_dst_port=65000,
                                timeout=within_sec(5)))
    f1 = sender.send_udp(router_mac, '172.16.2.1', 29,
                         src_port=9, dst_port=65000)
    wait_on_futures([f1, f2, f3])


@attr(version="v1.2.0")
@bindings(binding_multihost)
def test_floating_ip():
    """
    Title: Tests a floating IP.

    Scenario 1:
    When: a VM sends an ICMP echo request to a floating IP address
          (100.100.100.100).
    Then: the router performs DNAT on the message according to the rule chain
          set to the router,
    And: the receiver VM should receive the ICMP echo packet,
    And: the receiver sends back an ICMP reply with its original IP address
         as a source address.
    And: the router applies SNAT to the reply packet.
    And: the sender receives the reply with src address NATed to the floating IP
         address.
    """
    sender = BM.get_iface_for_port('bridge-000-001', 2)
    receiver = BM.get_iface_for_port('bridge-000-002', 2)
    # Reset in-/out-bound filters.
    unset_filters('router-000-001')
    feed_receiver_mac(receiver)

    f1 = async_assert_that(receiver, should_NOT_receive('dst host 172.16.2.1 and icmp',
                                             within_sec(10)))
    sender.ping_ipv4_addr('100.100.100.100')
    wait_on_futures([f1])

    # Configure floating IP address with the router
    set_filters('router-000-001', 'pre_filter_floating_ip',
                'post_filter_floating_ip')

    f1 = async_assert_that(receiver, receives('dst host 172.16.2.1 and icmp',
                                   within_sec(10)))
    f2 = async_assert_that(sender, receives('src host 100.100.100.100 and icmp',
                                 within_sec(10)))
    sender.ping_ipv4_addr('100.100.100.100')
    wait_on_futures([f1, f2])
