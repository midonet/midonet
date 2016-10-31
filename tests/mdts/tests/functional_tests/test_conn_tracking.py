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

from hamcrest.core import assert_that
from nose.plugins.attrib import attr

from mdts.lib.binding_manager import BindingManager
from mdts.lib.physical_topology_manager import PhysicalTopologyManager
from mdts.lib.virtual_topology_manager import VirtualTopologyManager
from mdts.tests.utils.asserts import receives, async_assert_that
from mdts.tests.utils.asserts import should_NOT_receive
from mdts.tests.utils.asserts import within_sec
from mdts.tests.utils.utils import bindings
from mdts.tests.utils.utils import wait_on_futures

import logging
import random
import time


LOG = logging.getLogger(__name__)
PTM = PhysicalTopologyManager(
    '../topologies/mmm_physical_test_conn_tracking.yaml')
VTM = VirtualTopologyManager(
    '../topologies/mmm_virtual_test_conn_tracking.yaml')
BM = BindingManager(PTM, VTM)


binding_multihost = {
    'description': 'spanning across multiple MMs',
    'bindings': [
        {'binding':
            {'device_name': 'bridge-000-001', 'port_id': 2,
             'host_id': 1, 'interface_id': 1}},
        {'binding':
            {'device_name': 'bridge-000-001', 'port_id': 3,
             'host_id': 2, 'interface_id': 2}},
    ]
}


def set_bridge_port_filters(bridge_name, port_id, inbound_filter_name,
                            outbound_filter_name):
    '''Sets an in-bound filter to a bridge.'''
    bridge_port = VTM.get_device_port(bridge_name, port_id)

    inbound_filter = None
    if inbound_filter_name:
        inbound_filter = VTM.get_chain(inbound_filter_name)
    outbound_filter = None
    if outbound_filter_name:
        outbound_filter = VTM.get_chain(outbound_filter_name)

    bridge_port.set_inbound_filter(inbound_filter)
    bridge_port.set_outbound_filter(outbound_filter)
    # Sleep here to make sure that the settings have been propagated.
    time.sleep(5)


def unset_bridge_port_filters(bridge_name, port_id):
    '''Sets an in-bound filter to a bridge.'''
    set_bridge_port_filters(bridge_name, port_id, None, None)


def get_random_port_num():
    '''Returns a random port number from a free port range.

    NOTE: Using a random number may cause test indeterminacy on a rare occasion.
    '''
    return random.randint(49152, 65535)


@attr(version="v1.2.0")
@bindings(binding_multihost)
def test_filtering_by_network_address():
    '''
    Title: Tests packets filtering based on network address

    Scenario:
    When: A VM sends UDP packets to another host on the same bridge.
    Then: The UDP packets reaches the receiver.
    Then: Filtering rule chains based on network address (IP address) are set on
          the bridge port that the receiver host is connected to.
    And: The UDP packets from the same sender do NOT reach the receiver.
    '''
    sender = BM.get_iface_for_port('bridge-000-001', 2)
    receiver = BM.get_iface_for_port('bridge-000-001', 3)

    # Reset in/out-bound filters.
    unset_bridge_port_filters('bridge-000-001', 3)

    port_num = get_random_port_num()
    # FIXME: do not use harcoded values!
    f1 = async_assert_that(receiver,
                           receives('dst host 172.16.1.2 and udp',
                                    within_sec(5)),
                           'No filtering: receives UDP packets from sender.')
    f2 = sender.send_udp('aa:bb:cc:00:01:02', '172.16.1.2', 41,
                         src_port=port_num, dst_port=port_num)
    wait_on_futures([f1, f2])

    # Set a filtering rule based on network address.
    set_bridge_port_filters('bridge-000-001', 3, 'connection_tracking_nw_in',
                            'connection_tracking_nw_out')

    f1 = async_assert_that(receiver, should_NOT_receive(
        'dst host 172.16.1.2 and udp',
        within_sec(5)),
        'Packets are filtered based on IP address.')
    f2 = sender.send_udp('aa:bb:cc:00:01:02', '172.16.1.2', 41,
                         src_port=port_num, dst_port=port_num)
    wait_on_futures([f1, f2])


@attr(version="v1.2.0")
@bindings(binding_multihost)
def test_connection_tracking_by_network_addres():
    '''
    Title: Tests NW address based connection tracking.

    Scenario:
    When: A VM, supposedly inside a FW, sends UDP packets to another host,
          supposedly outside the FS, on the same bridge.
    And: The host outside the FW receives the UDP packets.
    Then: A connection-tracking-based peep hole is established.
    And: The outside host now can send UDP packets to the inside host.
    '''
    outside = BM.get_iface_for_port('bridge-000-001', 2)
    inside = BM.get_iface_for_port('bridge-000-001', 3)

    # Set a filtering rule based on ip address.
    set_bridge_port_filters('bridge-000-001', 3, 'connection_tracking_nw_in',
                            'connection_tracking_nw_out')

    # Send forward packets to set up a connection-tracking based peep hole in
    # the filter.
    port_num = get_random_port_num()
    f1 = async_assert_that(outside,
                           receives('dst host 172.16.1.1 and udp',
                                    within_sec(5)),
                           'Outside host receives forward packets from inside.')
    f2 = inside.send_udp('aa:bb:cc:00:01:01', '172.16.1.1', 41,
                         src_port=port_num, dst_port=port_num)
    wait_on_futures([f1, f2])

    # Verify the peep hole.
    f1 = async_assert_that(inside,
                           receives('dst host 172.16.1.2 and udp',
                                    within_sec(5)),
                           'Outside host can send packets to inside '
                           'via a peep hole.')
    f2 = outside.send_udp('aa:bb:cc:00:01:02', '172.16.1.2', 41,
                          src_port=port_num, dst_port=port_num)
    wait_on_futures([f1, f2])


@attr(version="v1.2.0")
@bindings(binding_multihost)
def test_filtering_by_dl():
    '''
    Title: Tests dl-based packet filtering.

    Scenario:
    When: A VM sends UDP packets to another host on the same bridge.
    Then: The UDP packets reach the receiver without filtering rule chains.
    Then: A filtering rule chain based on mac address is set on the bridge.
    And: UDP packets from the same host do NOT reach the same destination host.
    '''
    outside = BM.get_iface_for_port('bridge-000-001', 2)
    inside = BM.get_iface_for_port('bridge-000-001', 3)

    # Reset an in-bound filter.
    unset_bridge_port_filters('bridge-000-001', 3)

    port_num = get_random_port_num()
    f1 = async_assert_that(
        inside,
        receives('dst host 172.16.1.2 and udp',
                 within_sec(5)),
        'No filtering: inside receives UDP packets from outside.')
    f2 = outside.send_udp('aa:bb:cc:00:01:02', '172.16.1.2', 41,
                          src_port=port_num, dst_port=port_num)
    wait_on_futures([f1, f2])

    # Set a filtering rule based on mac addresses
    set_bridge_port_filters('bridge-000-001', 3, 'connection_tracking_dl_in',
                            'connection_tracking_dl_out')

    f1 = async_assert_that(inside,
                           should_NOT_receive(
                               'dst host 172.16.1.2 and udp',
                               within_sec(5)),
                           'Packets are filtered based on mac address.')
    f2 = outside.send_udp('aa:bb:cc:00:01:02', '172.16.1.2', 41,
                          src_port=port_num, dst_port=port_num)
    wait_on_futures([f1, f2])


@attr(version="v1.2.0")
@bindings(binding_multihost)
def test_connection_tracking_with_drop_by_dl():
    '''
    Title: Tests dl-based connection tracking.

    Scenario:
    When: A VM inside a FW sends UDP packets to a VM outside.
    And: The outside receives the UDP packets.
    Then: A connection-tracking-based peep hole is established.
    And: The outside now can send UDP packets to the inside.
    '''
    outside = BM.get_iface_for_port('bridge-000-001', 2)
    inside = BM.get_iface_for_port('bridge-000-001', 3)

    # Set a filtering rule based on mac addresses
    set_bridge_port_filters('bridge-000-001', 3, 'connection_tracking_dl_in',
                            'connection_tracking_dl_out')

    # Send forward packets to set up a connection-tracking based peep hole in
    # the filter.
    port_num = get_random_port_num()
    f1 = async_assert_that(outside,
                           receives('dst host 172.16.1.1 and udp',
                                    within_sec(5)),
                           'The outside host receives forward packets '
                           'from the inside.')
    f2 = inside.send_udp('aa:bb:cc:00:01:01', '172.16.1.1', 41,
                         src_port=port_num, dst_port=port_num)
    wait_on_futures([f1, f2])

    # Verify the peep hole.
    f1 = async_assert_that(inside,
                           receives('dst host 172.16.1.2 and udp',
                                    within_sec(5)),
                           'The outside host can now send packets to the inside'
                           'via a peep hole.')
    f2 = outside.send_udp('aa:bb:cc:00:01:02', '172.16.1.2', 41,
                          src_port=port_num, dst_port=port_num)
    wait_on_futures([f1, f2])
