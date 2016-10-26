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
from mdts.lib.physical_topology_manager import PhysicalTopologyManager
from mdts.lib.virtual_topology_manager import VirtualTopologyManager
from mdts.lib.binding_manager import BindingManager
from mdts.lib.failure.no_failure import NoFailure
from mdts.tests.utils.asserts import *
from mdts.tests.utils.utils import bindings
from mdts.tests.utils.utils import failures
from mdts.tests.utils.utils import get_midonet_api
from mdts.tests.utils.utils import wait_on_futures

from nose.plugins.attrib import attr
from nose.tools import nottest

from hamcrest import *

import logging
import time

LOG = logging.getLogger(__name__)

PTM = PhysicalTopologyManager('../topologies/mmm_physical_test_l2insertion.yaml')
VTM = VirtualTopologyManager('../topologies/mmm_virtual_test_l2insertion.yaml')
BM = BindingManager(PTM, VTM)

# Not enabled for tests, but useful for debugging
bindings_single = {
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
              'host_id': 1, 'interface_id': 3}},
        {'binding':
             {'device_name': 'bridge-000-001', 'port_id': 4,
              'host_id': 1, 'interface_id': 4}},
        {'binding':
             {'device_name': 'bridge-000-002', 'port_id': 1,
              'host_id': 1, 'interface_id': 5}},
    ]
}

bindings_split = {
    'description': 'on 2 MM, one port and one service on each',
    'bindings': [
        {'binding':
             {'device_name': 'bridge-000-001', 'port_id': 1,
              'host_id': 1, 'interface_id': 1}},
        {'binding':
             {'device_name': 'bridge-000-001', 'port_id': 2,
              'host_id': 2, 'interface_id': 2}},
        {'binding':
             {'device_name': 'bridge-000-001', 'port_id': 3,
              'host_id': 1, 'interface_id': 3}},
        {'binding':
             {'device_name': 'bridge-000-001', 'port_id': 4,
              'host_id': 2, 'interface_id': 4}},
        {'binding':
             {'device_name': 'bridge-000-002', 'port_id': 1,
              'host_id': 1, 'interface_id': 5}},
    ]
}

bindings_spread = {
    'description': 'on 3 MM, services on separate host',
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
        {'binding':
             {'device_name': 'bridge-000-001', 'port_id': 4,
              'host_id': 3, 'interface_id': 4}},
        {'binding':
             {'device_name': 'bridge-000-002', 'port_id': 1,
              'host_id': 3, 'interface_id': 5}},
    ]
}

bindings_spread2 = {
    'description': 'on 3 MM, complicated config for flow state',
    'bindings': [
        {'binding':
             {'device_name': 'bridge-000-001', 'port_id': 1,
              'host_id': 3, 'interface_id': 1}},
        {'binding':
             {'device_name': 'bridge-000-001', 'port_id': 2,
              'host_id': 2, 'interface_id': 2}},
        {'binding':
             {'device_name': 'bridge-000-001', 'port_id': 3,
              'host_id': 3, 'interface_id': 3}},
        {'binding':
             {'device_name': 'bridge-000-001', 'port_id': 4,
              'host_id': 3, 'interface_id': 4}},
        {'binding':
             {'device_name': 'bridge-000-002', 'port_id': 1,
              'host_id': 1, 'interface_id': 5}},
    ]
}

bindings_spread3 = {
    'description': 'on 3 MM, super complicated config for flow state',
    'bindings': [
        {'binding':
             {'device_name': 'bridge-000-001', 'port_id': 1,
              'host_id': 2, 'interface_id': 1}},
        {'binding':
             {'device_name': 'bridge-000-001', 'port_id': 2,
              'host_id': 2, 'interface_id': 2}},
        {'binding':
             {'device_name': 'bridge-000-001', 'port_id': 3,
              'host_id': 3, 'interface_id': 3}},
        {'binding':
             {'device_name': 'bridge-000-001', 'port_id': 4,
              'host_id': 3, 'interface_id': 4}},
        {'binding':
             {'device_name': 'bridge-000-002', 'port_id': 1,
              'host_id': 1, 'interface_id': 5}},
    ]
}


@attr(version="v1.2.0")
@failures(NoFailure())
@bindings(bindings_split, bindings_spread)
def test_basic_l2insertion():
    """
    Title: Basic insertion functionallity

    L2Service insertion on 1, which redirects traffic through port 3.
    Good traffic should go through. Traffic that matches the bad pattern
    should be dropped.
    """
    api = get_midonet_api()

    service_port = BM.get_iface_for_port('bridge-000-001', 2)

    fakesnort = FakeSnort(service_port, "deadbeef")
    fakesnort.run()
    try:
        insertion_port = BM.get_iface_for_port('bridge-000-001', 1)
        insertion = api.add_l2insertion()
        insertion.srv_port(service_port.vport_id)
        insertion.port(insertion_port.vport_id)
        insertion.position(1)
        insertion.fail_open(False)
        insertion.mac(insertion_port.get_mac_addr())
        insertion.create()

        time.sleep(5)

        other_port = BM.get_iface_for_port('bridge-000-001', 3)

        rcv_filter = 'ether dst %s and icmp[icmptype] == 8' % insertion_port.get_mac_addr()
        LOG.info("Sending good packet")
        f1 = async_assert_that(service_port,
                               receives(rcv_filter, within_sec(10)))
        f2 = async_assert_that(insertion_port,
                               receives(rcv_filter, within_sec(10)))
        ping_port(other_port, insertion_port, data="f00b4c")
        wait_on_futures([f1, f2])

        LOG.info("Sending dead beef")
        f1 = async_assert_that(service_port,
                               receives(rcv_filter, within_sec(10)))
        f2 = async_assert_that(insertion_port,
                               should_NOT_receive(rcv_filter, within_sec(10)))
        ping_port(other_port, insertion_port, data="deadbeef",
                  should_succeed=False)
        wait_on_futures([f1, f2])

        LOG.info("Sending another good packet")
        f1 = async_assert_that(service_port,
                               receives(rcv_filter, within_sec(10)))
        f2 = async_assert_that(insertion_port,
                               receives(rcv_filter, within_sec(10)))
        ping_port(other_port, insertion_port, data="f00b4c")
        wait_on_futures([f1, f2])

        LOG.info("Sending bad packet the other way")
        f1 = async_assert_that(service_port,
                               receives(rcv_filter_ret, within_sec(10)))
        f2 = async_assert_that(other_port,
                               should_NOT_receive(rcv_filter_ret, within_sec(10)))
        ping_port(insertion_port, other_port, data="deadbeef",
                  should_succeed=False)
        wait_on_futures([f1, f2])

        LOG.info("Sending good packet the other way")
        f1 = async_assert_that(service_port,
                               receives(rcv_filter_ret, within_sec(10)))
        f2 = async_assert_that(other_port,
                               receives(rcv_filter_ret, within_sec(10)))
        ping_port(insertion_port, other_port, data="b00f00")
        wait_on_futures([f1, f2])
    finally:
        fakesnort.kill()

@attr(version="v1.2.0")
@failures(NoFailure())
@bindings(bindings_split, bindings_spread)
def test_multi_l2insertion():
    """
    Title: Multiple insertions on a port

    2 L2 insertions on a port, each with a different bad pattern.
    The bad packets matching the different patterns should be blocked at
    different points.

    Verify that packets are blocked in the same order regardless of the
    direction.
    """
    api = get_midonet_api()

    service_port1 = BM.get_iface_for_port('bridge-000-001', 2)
    service_port2 = BM.get_iface_for_port('bridge-000-001', 3)

    fakesnort1 = FakeSnort(service_port1, "deadbeef")
    fakesnort2 = FakeSnort(service_port2, "cafef00d")
    try:
        fakesnort1.run()
        fakesnort2.run()

        insertion_port = BM.get_iface_for_port('bridge-000-001', 1)

        insertion1 = api.add_l2insertion()
        insertion1.srv_port(service_port1.vport_id)
        insertion1.port(insertion_port.vport_id)
        insertion1.position(1)
        insertion1.fail_open(False)
        insertion1.vlan(1)
        insertion1.mac(insertion_port.get_mac_addr())
        insertion1.create()

        insertion2 = api.add_l2insertion()
        insertion2.srv_port(service_port2.vport_id)
        insertion2.port(insertion_port.vport_id)
        insertion2.position(2)
        insertion2.fail_open(False)
        insertion2.vlan(2)
        insertion2.mac(insertion_port.get_mac_addr())
        insertion2.create()

        time.sleep(5)

        rcv_filter = 'ether dst %s and icmp[icmptype] == 8' % insertion_port.get_mac_addr()
        rcv_filter_ret = 'ether src %s and icmp[icmptype] == 8' % insertion_port.get_mac_addr()

        other_port = BM.get_iface_for_port('bridge-000-001', 4)

        LOG.info("Sending good packet")
        f1 = async_assert_that(service_port1,
                               receives(rcv_filter, within_sec(10)))
        f2 = async_assert_that(service_port2,
                               receives(rcv_filter, within_sec(10)))
        f3 = async_assert_that(insertion_port,
                               receives(rcv_filter, within_sec(10)))
        ping_port(other_port, insertion_port, data="f00b4c")
        wait_on_futures([f1, f2, f3])

        LOG.info("Sending dead beef")
        f1 = async_assert_that(service_port1,
                               receives(rcv_filter, within_sec(10)))
        f2 = async_assert_that(service_port2,
                               should_NOT_receive(rcv_filter, within_sec(10)))
        f3 = async_assert_that(insertion_port,
                               should_NOT_receive(rcv_filter, within_sec(10)))
        ping_port(other_port, insertion_port, data="deadbeef", should_succeed=False)
        wait_on_futures([f1, f2, f3])

        LOG.info("Sending cafe food")
        f1 = async_assert_that(service_port1,
                               receives(rcv_filter, within_sec(10)))
        f2 = async_assert_that(service_port2,
                               receives(rcv_filter, within_sec(10)))
        f3 = async_assert_that(insertion_port,
                               should_NOT_receive(rcv_filter, within_sec(10)))
        ping_port(other_port, insertion_port, data="cafef00d", should_succeed=False)
        wait_on_futures([f1, f2, f3])

        LOG.info("Sending another good packet")
        f1 = async_assert_that(service_port1,
                               receives(rcv_filter, within_sec(10)))
        f2 = async_assert_that(service_port2,
                               receives(rcv_filter, within_sec(10)))
        f3 = async_assert_that(insertion_port,
                               receives(rcv_filter, within_sec(10)))
        ping_port(other_port, insertion_port, data="beeff00d")
        wait_on_futures([f1, f2, f3])

        LOG.info("Sending dead beef the other way")
        f1 = async_assert_that(service_port1,
                               receives(rcv_filter_ret, within_sec(10)))
        f2 = async_assert_that(service_port2,
                               should_NOT_receive(rcv_filter_ret, within_sec(10)))
        f3 = async_assert_that(other_port,
                               should_NOT_receive(rcv_filter_ret, within_sec(10)))
        ping_port(insertion_port, other_port, data="deadbeef", should_succeed=False)
        wait_on_futures([f1, f2, f3])

        LOG.info("Sending cafe food the other way")
        f1 = async_assert_that(service_port1,
                               receives(rcv_filter_ret, within_sec(10)))
        f2 = async_assert_that(service_port2,
                               receives(rcv_filter_ret, within_sec(10)))
        f3 = async_assert_that(other_port,
                               should_NOT_receive(rcv_filter_ret, within_sec(10)))
        ping_port(insertion_port, other_port, data="cafef00d", should_succeed=False)
        wait_on_futures([f1, f2, f3])

        LOG.info("Sending another good packet the other way")
        f1 = async_assert_that(service_port1,
                               receives(rcv_filter_ret, within_sec(10)))
        f2 = async_assert_that(service_port2,
                               receives(rcv_filter_ret, within_sec(10)))
        f3 = async_assert_that(other_port,
                               receives(rcv_filter_ret, within_sec(10)))
        ping_port(insertion_port, other_port, data="beeff00d")
        wait_on_futures([f1, f2, f3])

    finally:
        fakesnort1.kill()
        fakesnort2.kill()

@attr(version="v1.2.0")
@failures(NoFailure())
@bindings(bindings_split, bindings_spread3)
def test_l2insertion_with_flowstate():
    """
    Title: Test insertions with flow state

    2 L2 insertions on a port, each with a different bad pattern.
    Packets are sent over DNAT so that flow state is generated and necessary to
    return the packet.

    The bad packets matching the different patterns should be blocked at
    different points.

    Verify that packets are blocked in the same order regardless of the
    direction.
    """
    api = get_midonet_api()

    router = VTM.get_router('router-000-001')
    conntrack_filter = VTM.get_chain('conntrack_filter_001')
    router.set_inbound_filter(conntrack_filter)

    # Sleep here to make sure that the settings have been propagated.
    time.sleep(5)

    service_port1 = BM.get_iface_for_port('bridge-000-001', 2)
    service_port2 = BM.get_iface_for_port('bridge-000-001', 3)

    fakesnort1 = FakeSnort(service_port1, "deadbeef")
    fakesnort2 = FakeSnort(service_port2, "cafef00d")
    try:
        fakesnort1.run()
        fakesnort2.run()

        insertion_port = BM.get_iface_for_port('bridge-000-001', 1)
        other_port = BM.get_iface_for_port('bridge-000-002', 1)

        insertion1 = api.add_l2insertion()
        insertion1.srv_port(service_port1.vport_id)
        insertion1.port(insertion_port.vport_id)
        insertion1.position(1)
        insertion1.fail_open(False)
        insertion1.vlan(1)
        insertion1.mac(insertion_port.get_mac_addr())
        insertion1.create()

        insertion2 = api.add_l2insertion()
        insertion2.srv_port(service_port2.vport_id)
        insertion2.port(insertion_port.vport_id)
        insertion2.position(2)
        insertion2.fail_open(False)
        insertion2.vlan(2)
        insertion2.mac(insertion_port.get_mac_addr())
        insertion2.create()

        time.sleep(5)

        rcv_filter = 'ip dst %s and icmp[icmptype] == 8' % insertion_port.get_ip()

        LOG.info("Sending good packet")
        f1 = async_assert_that(service_port1,
                               receives(rcv_filter, within_sec(10)))
        f2 = async_assert_that(service_port2,
                               receives(rcv_filter, within_sec(10)))
        f3 = async_assert_that(insertion_port,
                               receives(rcv_filter, within_sec(10)))
        ping_port(other_port, insertion_port, data="f00b4c")
        wait_on_futures([f1, f2, f3])

        LOG.info("Sending dead beef")
        f1 = async_assert_that(service_port1,
                               receives(rcv_filter, within_sec(10)))
        f2 = async_assert_that(service_port2,
                               should_NOT_receive(rcv_filter, within_sec(10)))
        f3 = async_assert_that(insertion_port,
                               should_NOT_receive(rcv_filter, within_sec(10)))
        ping_port(other_port, insertion_port,
                  data="deadbeef", should_succeed=False)
        wait_on_futures([f1, f2, f3])

        LOG.info("Sending cafe food")
        f1 = async_assert_that(service_port1,
                               receives(rcv_filter, within_sec(10)))
        f2 = async_assert_that(service_port2,
                               receives(rcv_filter, within_sec(10)))
        f3 = async_assert_that(insertion_port,
                               should_NOT_receive(rcv_filter, within_sec(10)))
        ping_port(other_port, insertion_port,
                  data="cafef00d", should_succeed=False)
        wait_on_futures([f1, f2, f3])

        LOG.info("Sending another good packet")
        f1 = async_assert_that(service_port1,
                               receives(rcv_filter, within_sec(10)))
        f2 = async_assert_that(service_port2,
                               receives(rcv_filter, within_sec(10)))
        f3 = async_assert_that(insertion_port,
                               receives(rcv_filter, within_sec(10)))
        ping_port(other_port, insertion_port, data="beeff00d")
        wait_on_futures([f1, f2, f3])
    finally:
        fakesnort1.kill()
        fakesnort2.kill()

@attr(version="v1.2.0")
@failures(NoFailure())
@bindings(bindings_split, bindings_spread)
def test_l2insertion_both_ends_protected():
    """
    Title: Test insertions with both ends protected

    2 L2 insertions on 2 ports, each insertion with a different bad pattern.

    The bad packets matching the different patterns should be blocked at
    different insertions depending on the direction of the packet.

    Verify that packets are blocked in the same order regardless of the
    direction.
    """
    api = get_midonet_api()

    service_port1 = BM.get_iface_for_port('bridge-000-001', 2)
    service_port2 = BM.get_iface_for_port('bridge-000-001', 3)

    fakesnort1 = FakeSnort(service_port1, "deadbeef")
    fakesnort2 = FakeSnort(service_port2, "cafef00d")
    try:
        fakesnort1.run()
        fakesnort2.run()

        insertion_port1 = BM.get_iface_for_port('bridge-000-001', 1)
        insertion_port2 = BM.get_iface_for_port('bridge-000-001', 4)

        insertion1 = api.add_l2insertion()
        insertion1.srv_port(service_port1.vport_id)
        insertion1.port(insertion_port1.vport_id)
        insertion1.position(1)
        insertion1.fail_open(False)
        insertion1.vlan(1)
        insertion1.mac(insertion_port1.get_mac_addr())
        insertion1.create()

        insertion2 = api.add_l2insertion()
        insertion2.srv_port(service_port2.vport_id)
        insertion2.port(insertion_port1.vport_id)
        insertion2.position(2)
        insertion2.fail_open(False)
        insertion2.vlan(2)
        insertion2.mac(insertion_port1.get_mac_addr())
        insertion2.create()

        insertion3 = api.add_l2insertion()
        insertion3.srv_port(service_port1.vport_id)
        insertion3.port(insertion_port2.vport_id)
        insertion3.position(1)
        insertion3.fail_open(False)
        insertion3.vlan(3)
        insertion3.mac(insertion_port2.get_mac_addr())
        insertion3.create()

        insertion4 = api.add_l2insertion()
        insertion4.srv_port(service_port2.vport_id)
        insertion4.port(insertion_port2.vport_id)
        insertion4.position(2)
        insertion4.fail_open(False)
        insertion4.vlan(4)
        insertion4.mac(insertion_port2.get_mac_addr())
        insertion4.create()

        time.sleep(5)

        rcv_filter = 'ip dst %s and icmp[icmptype] == 8' % insertion_port2.get_ip()

        LOG.info("Sending good packet")
        f1 = async_assert_that(service_port1,
                               receives(rcv_filter, within_sec(10), count=2))
        f2 = async_assert_that(service_port2,
                               receives(rcv_filter, within_sec(10), count=2))
        f3 = async_assert_that(insertion_port2,
                               receives(rcv_filter, within_sec(10)))
        ping_port(insertion_port1, insertion_port2, data="f00b4c")
        wait_on_futures([f1, f2, f3])

        LOG.info("Sending dead beef")
        f1 = async_assert_that(service_port1,
                               receives(rcv_filter, within_sec(10)))
        f2 = async_assert_that(service_port2,
                               should_NOT_receive(rcv_filter, within_sec(10)))
        f3 = async_assert_that(insertion_port2,
                               should_NOT_receive(rcv_filter, within_sec(10)))
        ping_port(insertion_port1, insertion_port2,
                  data="deadbeef", should_succeed=False)
        wait_on_futures([f1, f2, f3])

        LOG.info("Sending cafe food")
        f1 = async_assert_that(service_port1,
                               receives(rcv_filter, within_sec(10)))
        f2 = async_assert_that(service_port2,
                               receives(rcv_filter, within_sec(10)))
        f3 = async_assert_that(insertion_port2,
                               should_NOT_receive(rcv_filter, within_sec(10)))
        ping_port(insertion_port1, insertion_port2,
                  data="cafef00d", should_succeed=False)
        wait_on_futures([f1, f2, f3])
    finally:
        fakesnort2.kill()
        fakesnort1.kill()


@attr(version="v1.2.0")
@failures(NoFailure())
@bindings(bindings_split, bindings_spread)
def test_l2insertion_fail_open():
    """
    Title: Test insertions with fail open

    2 L2 insertions on a port, each insertion with a different bad pattern.
    One of the insertions is set to fail open.

    Test both bad patterns can't get through.

    Take down the fail open insertion.
    Verify that that bad pattern can now get through.

    Take down the non fail open insertion.
    Verify that no traffic can get through.

    Also verify both directions
    """
    api = get_midonet_api()

    service_port1 = BM.get_iface_for_port('bridge-000-001', 2)
    service_port2 = BM.get_iface_for_port('bridge-000-001', 3)

    fakesnort1 = FakeSnort(service_port1, "deadbeef")
    fakesnort2 = FakeSnort(service_port2, "cafef00d")
    try:
        fakesnort1.run()
        fakesnort2.run()

        insertion_port = BM.get_iface_for_port('bridge-000-001', 1)
        other_port = BM.get_iface_for_port('bridge-000-001', 4)

        insertion1 = api.add_l2insertion()
        insertion1.srv_port(service_port1.vport_id)
        insertion1.port(insertion_port.vport_id)
        insertion1.position(1)
        insertion1.fail_open(True)
        insertion1.vlan(1)
        insertion1.mac(insertion_port.get_mac_addr())
        insertion1.create()

        insertion2 = api.add_l2insertion()
        insertion2.srv_port(service_port2.vport_id)
        insertion2.port(insertion_port.vport_id)
        insertion2.position(2)
        insertion2.fail_open(False)
        insertion2.vlan(2)
        insertion2.mac(insertion_port.get_mac_addr())
        insertion2.create()

        time.sleep(5)

        rcv_filter = 'ip dst %s and icmp[icmptype] == 8' % insertion_port.get_ip()
        rcv_filter_ret = 'ip src %s and icmp[icmptype] == 8' % insertion_port.get_ip()

        LOG.info("Sending good packet")
        f1 = async_assert_that(service_port1,
                               receives(rcv_filter, within_sec(10)))
        f2 = async_assert_that(service_port2,
                               receives(rcv_filter, within_sec(10)))
        f3 = async_assert_that(insertion_port,
                               receives(rcv_filter, within_sec(10)))
        ping_port(other_port, insertion_port, data="f00b4c")
        wait_on_futures([f1, f2, f3])

        LOG.info("Sending dead beef")
        f1 = async_assert_that(service_port1,
                               receives(rcv_filter, within_sec(10)))
        f2 = async_assert_that(service_port2,
                               should_NOT_receive(rcv_filter, within_sec(10)))
        f3 = async_assert_that(insertion_port,
                               should_NOT_receive(rcv_filter, within_sec(10)))
        ping_port(other_port, insertion_port,
                  data="deadbeef", should_succeed=False)
        wait_on_futures([f1, f2, f3])

        LOG.info("Sending cafe food")
        f1 = async_assert_that(service_port1,
                               receives(rcv_filter, within_sec(10)))
        f2 = async_assert_that(service_port2,
                               receives(rcv_filter, within_sec(10)))
        f3 = async_assert_that(insertion_port,
                               should_NOT_receive(rcv_filter, within_sec(10)))
        ping_port(other_port, insertion_port,
                  data="cafef00d", should_succeed=False)
        wait_on_futures([f1, f2, f3])

        LOG.info("Sending dead beef the other way")
        f1 = async_assert_that(service_port1,
                               receives(rcv_filter_ret, within_sec(10)))
        f2 = async_assert_that(service_port2,
                               should_NOT_receive(rcv_filter_ret, within_sec(10)))
        f3 = async_assert_that(other_port,
                               should_NOT_receive(rcv_filter_ret, within_sec(10)))
        ping_port(insertion_port, other_port,
                  data="deadbeef", should_succeed=False)
        wait_on_futures([f1, f2, f3])

        set_interface_admin_down(service_port1)
        time.sleep(5) # give midolman a chance to see it

        LOG.info("Sending dead beef again")
        f1 = async_assert_that(service_port2,
                               receives(rcv_filter, within_sec(10)))
        f2 = async_assert_that(insertion_port,
                               receives(rcv_filter, within_sec(10)))
        ping_port(other_port, insertion_port, data="deadbeef")
        wait_on_futures([f1, f2])

        LOG.info("Sending dead beef the other way again")
        f1 = async_assert_that(service_port2,
                               receives(rcv_filter_ret, within_sec(10)))
        f2 = async_assert_that(other_port,
                               receives(rcv_filter_ret, within_sec(10)))
        ping_port(insertion_port, other_port, data="deadbeef")
        wait_on_futures([f1, f2])

        LOG.info("Sending cafe food again")
        f1 = async_assert_that(service_port2,
                               receives(rcv_filter, within_sec(10)))
        f2 = async_assert_that(insertion_port,
                               should_NOT_receive(rcv_filter, within_sec(10)))
        ping_port(other_port, insertion_port,
                  data="cafef00d", should_succeed=False)
        wait_on_futures([f1, f2])

        set_interface_admin_down(service_port2)
        time.sleep(5)

        # nothing should get through
        LOG.info("Sending good packet")
        f1 = async_assert_that(insertion_port,
                               should_NOT_receive(rcv_filter, within_sec(10)))
        ping_port(other_port, insertion_port,
                  data="f00b4c", should_succeed=False)
        wait_on_futures([f1])

        LOG.info("Sending good packet the other way")
        f1 = async_assert_that(other_port,
                               should_NOT_receive(rcv_filter_ret, within_sec(10)))
        ping_port(insertion_port, other_port,
                  data="f00b4c", should_succeed=False)
        wait_on_futures([f1])
    finally:
        fakesnort2.kill()
        fakesnort1.kill()

def ping_port(source_port, dest_port, data,
              count=1, should_succeed=True):
    f = source_port.ping4(dest_port, size=100, data=data, count=count)
    output_stream, exec_id = f.result()
    exit_status = source_port.compute_host.check_exit_status(exec_id,
                                                             output_stream,
                                                             timeout=20)
    if should_succeed:
        assert_that(exit_status, equal_to(0), "Ping did not return any data")
    else:
        assert_that(exit_status, is_not(equal_to(0)), "Ping should have failed")

def set_interface_admin_down(interface):
    interface.compute_host.exec_command(
        "ip link set down dev %s" % interface.get_binding_ifname(), stream=True)

class FakeSnort(object):
    def __init__(self, port, pattern):
        self._port = port
        iface = port.get_ifname()
        self._cmd = "fake_snort --interface %s --block-pattern %s" % (iface, pattern)
        self._killname = "fake_snort_%s" % iface

    def run(self):
        return self._port.execute(self._cmd)

    def kill(self):
        self._port.execute("pkill -9 %s" % self._killname)
