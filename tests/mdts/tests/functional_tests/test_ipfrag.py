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
import random
import subprocess
import time

PTM = PhysicalTopologyManager('../topologies/mmm_physical_test_ipfrag.yaml')
VTM = VirtualTopologyManager('../topologies/mmm_virtual_test_ipfrag.yaml')
BM = BindingManager(PTM, VTM)

binding_multi_bridge = {
    'description': 'on multiple MM with bridge through tunneling',
    'bindings': [
        {'binding':
            {'device_name': 'bridge-000-001', 'port_id': 2,
             'host_id': 1, 'interface_id': 1}},
        {'binding':
            {'device_name': 'bridge-000-001', 'port_id': 3,
             'host_id': 2, 'interface_id': 2}},
        {'binding':
            {'device_name': 'bridge-000-002', 'port_id': 2,
             'host_id': 1, 'interface_id': 3}},
    ]
}

binding_multi_router = {
    'description': 'on multiple MM with router through tunneling',
    'bindings': [
        {'binding':
            {'device_name': 'bridge-000-001', 'port_id': 2,
             'host_id': 1, 'interface_id': 1}},
        {'binding':
            {'device_name': 'bridge-000-001', 'port_id': 3,
             'host_id': 1, 'interface_id': 2}},
        {'binding':
            {'device_name': 'bridge-000-002', 'port_id': 2,
             'host_id': 2, 'interface_id': 3}},
    ]
}

# See MN-1758.
#
# Drop flows for ip fragmentation are temporary because we want flows to
# come to userspace once in a while, it give MM the chance to send ICMP
# fragmentation needed messages.
#
# When a test case leaves such a flow installed, the flow can pill over
# to the next test. This method prevents that.
#
# The wait time that guarantees that flows will be ejected is 15 seconds.
# 5 seconds is their hard expiration time, and 10 seconds is the flow
# expiration check interval in the flow controller.


def let_temporary_frag_needed_flows_expire():
    time.sleep(15)


def _async_assert_receives_icmp_frag_needed(sender, should_receive):
    icmp_filter = 'icmp[icmptype] == icmp-unreach and icmp[icmpcode] == 4'
    if should_receive:
        return async_assert_that(sender,
                                 receives(icmp_filter,
                                          within_sec(5)))
    else:
        return async_assert_that(sender,
                                 should_NOT_receive(icmp_filter,
                                                    within_sec(5)))


def _send_icmp(sender, receiver, payload, target_ipv4,
               expect, expect_icmp_frag_needed=False):
    f1 = async_assert_that(receiver,
                           expect('dst host %s and icmp' % target_ipv4,
                                  within_sec(5)))

    f2 = _async_assert_receives_icmp_frag_needed(sender,
                                                 expect_icmp_frag_needed)

    time.sleep(1)

    f3 = sender.ping4(receiver, 0.5, 3, False, payload)

    wait_on_futures([f1, f2, f3])


def _test_icmp(sender, receiver, target_ipv4, filter_resource, is_router):

    # See https://midobugs.atlassian.net/browse/MN-79
    receiver.ping4(sender, 0.5, 3, True)

    # IP packet length is 1501 which generates 2 fragments
    payload = 1473

    try:
        _send_icmp(sender, receiver, payload, target_ipv4, receives)
    except subprocess.CalledProcessError:
        raise

    chain = VTM.get_chain('reject_fragments')
    filter_resource.set_inbound_filter(chain)

    try:
        _send_icmp(sender, receiver, payload, target_ipv4,
                   should_NOT_receive, expect_icmp_frag_needed=is_router)
    except subprocess.CalledProcessError:
        pass

    # Reset the filter for the next bindings
    # TODO(tomoe): push this to per function teardown hook.
    filter_resource.set_inbound_filter(None)


@attr(version="v1.2.0")
@bindings(binding_multi_bridge)
def test_icmp_bridge():
    let_temporary_frag_needed_flows_expire()

    _test_icmp(BM.get_iface_for_port('bridge-000-001', 2),
               BM.get_iface_for_port('bridge-000-001', 3),
               '172.16.1.2',
               VTM.get_bridge('bridge-000-001'),
               is_router=False)


@attr(version="v1.2.0")
@bindings(binding_multi_router)
def test_icmp_router():
    let_temporary_frag_needed_flows_expire()

    _test_icmp(BM.get_iface_for_port('bridge-000-001', 2),
               BM.get_iface_for_port('bridge-000-002', 2),
               '172.16.2.1',
               VTM.get_router('router-000-001'),
               is_router=True)


def _send_udp(sender, receiver, target_hw, target_ipv4, parms, payload,
              expect, expect_icmp_frag_needed=False):
    f1 = async_assert_that(receiver,
                           expect('dst host %s and udp' % target_ipv4,
                                  within_sec(5)))

    f2 = _async_assert_receives_icmp_frag_needed(sender,
                                                 expect_icmp_frag_needed)

    time.sleep(1)

    f3 = sender.send_packet(target_hw, target_ipv4, 'udp', parms, payload,
                            1, 3, False)

    wait_on_futures([f1, f2, f3])


def _test_udp(sender, receiver, target_hw, target_ipv4,
              filter_resource, is_router):

    ident = random.randint(10000, 20000)

    head = 'mf,proto=17,id=%d,sp=9,dp=9,iplen=1500,len=1481' % (ident,)
    tail = 'frag=185,proto=17,id=%d' % (ident,)

    # hex file zero-1472 is not used anymore, just define the size
    try:
        _send_udp(sender, receiver, target_hw, target_ipv4, head, 1472,
                  receives)
    except subprocess.CalledProcessError:
        raise

    try:
        _send_udp(sender, receiver, target_hw, target_ipv4, tail, 1,
                  receives)
    except subprocess.CalledProcessError:
        raise

    chain = VTM.get_chain('reject_fragments')
    filter_resource.set_inbound_filter(chain)

    try:
        _send_udp(sender, receiver, target_hw, target_ipv4, head, 1472,
                  should_NOT_receive, expect_icmp_frag_needed=is_router)
    except subprocess.CalledProcessError:
        pass

    try:
        _send_udp(sender, receiver, target_hw, target_ipv4, tail, 1,
                  should_NOT_receive)
    except subprocess.CalledProcessError:
        pass

    # Reset the filter for the next bindings
    # TODO(tomoe): push this to per function teardown hook.
    filter_resource.set_inbound_filter(None)


@attr(version="v1.2.0")
@bindings(binding_multi_bridge)
def test_udp_bridge():
    let_temporary_frag_needed_flows_expire()

    _test_udp(BM.get_iface_for_port('bridge-000-001', 2),
              BM.get_iface_for_port('bridge-000-001', 3),
              'aa:bb:cc:00:02:02',
              '172.16.1.2',
              VTM.get_bridge('bridge-000-001').get_port(2),
              is_router=False)


@attr(version="v1.2.0")
@bindings(binding_multi_router)
def test_udp_router():
    let_temporary_frag_needed_flows_expire()

    router = VTM.get_router('router-000-001')
    router_port = router.get_port(1)
    _test_udp(BM.get_iface_for_port('bridge-000-001', 2),
              BM.get_iface_for_port('bridge-000-002', 2),
              router_port.get_mn_resource().get_port_mac(),
              '172.16.2.1', router, is_router=True)
