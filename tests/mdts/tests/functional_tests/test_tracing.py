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
from mdts.services.service import get_container_by_hostname

import logging
import time

from uuid import UUID
from cassandra.cluster import Cluster
import cassandra

LOG = logging.getLogger(__name__)
PTM = PhysicalTopologyManager('../topologies/mmm_physical_test_tracing.yaml')
VTM = VirtualTopologyManager('../topologies/mmm_virtual_test_tracing.yaml')
BM = BindingManager(PTM, VTM)

cass_host = get_container_by_hostname('cassandra1').get_ip_address()

cassandra = Cluster([cass_host]).connect()
cassandra.default_timeout = 60.0

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

def get_flow_traces(tr):
    rows = cassandra.execute(
        """SELECT flowtraceid FROM midonetflowtracing.flows
           WHERE tracerequestid = %s""",
        [UUID(tr)])
    return [str(r.flowtraceid) for r in rows]

def get_hosts(tr, flowtraceid):
    rows = cassandra.execute(
        """SELECT host FROM midonetflowtracing.flow_events
           WHERE tracerequestid = %s AND flowtraceid = %s""",
        [UUID(tr), UUID(flowtraceid)])
    hosts = set()
    for r in rows:
        hosts.add(r.host)
    return hosts

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
def test_tracing_egress_matching():
    """
    Title: Tracing egress matching

    Scenario 1:
    When: a VM sends ICMP echo request wait for echo response
    Then: Trace data appears for the ingress and the egress host
    """
    tracerequest = VTM.get_tracerequest('ping-trace-request')
    try:
        tracerequest.set_enabled(True)
        time.sleep(5)

        flowtraces = get_flow_traces(tracerequest.get_id())
        assert (len(flowtraces) == 0)

        sender = BM.get_iface_for_port('bridge-000-001', 2)
        receiver = BM.get_iface_for_port('bridge-000-002', 2)

        feed_receiver_mac(receiver)

        f2 = async_assert_that(receiver, receives('dst host 172.16.2.1 and icmp',
                                                  within_sec(10)))
        f3 = async_assert_that(sender, receives('src host 172.16.2.1 and icmp',
                                                within_sec(10)))
        f1 = sender.ping_ipv4_addr('172.16.2.1')
        wait_on_futures([f1, f2, f3])

        time.sleep(5)

        flowtraces = get_flow_traces(tracerequest.get_id())
        assert (len(flowtraces) == 2)

        # ensure both packets were traced on both hosts
        assert(len(get_hosts(tracerequest.get_id(), flowtraces[0])) == 2)
        assert(len(get_hosts(tracerequest.get_id(), flowtraces[1])) == 2)
    finally:
        tracerequest.set_enabled(False)

@attr(version="v1.2.0")
@bindings(binding_multihost)
def test_tracing_egress_matching_over_nat():
    """
    Title: Tracing egress matching over nat

    Scenario 1:
    When: a VM sends ICMP echo request over nat wait for echo response
    Then: Trace data appears for the ingress and the egress host,
          but only as part of 2 flow traces (one for forward, one for return)
    """
    unset_filters('router-000-001')
    tracerequest = VTM.get_tracerequest('ping-trace-request')
    try:
        set_filters('router-000-001', 'pre_filter_001', 'post_filter_001')
        tracerequest.set_enabled(True)
        time.sleep(5)

        flowtraces = get_flow_traces(tracerequest.get_id())
        assert (len(flowtraces) == 0)

        sender = BM.get_iface_for_port('bridge-000-001', 2)
        receiver = BM.get_iface_for_port('bridge-000-002', 2)

        feed_receiver_mac(receiver)

        f2 = async_assert_that(receiver, receives('dst host 172.16.2.1 and icmp',
                                                  within_sec(10)))
        f3 = async_assert_that(sender, receives('src host 100.100.100.100 and icmp',
                                                within_sec(10)))
        f1 = sender.ping_ipv4_addr('100.100.100.100')
        wait_on_futures([f1, f2, f3])

        time.sleep(5)

        flowtraces = get_flow_traces(tracerequest.get_id())
        assert (len(flowtraces) == 2)

        # ensure both packets were traced on both hosts
        assert(len(get_hosts(tracerequest.get_id(), flowtraces[0])) == 2)
        assert(len(get_hosts(tracerequest.get_id(), flowtraces[1])) == 2)
    finally:
        tracerequest.set_enabled(False)
        unset_filters('router-000-001')

@attr(version="v1.2.0")
@bindings(binding_multihost)
def test_tracing_with_limit():
    """
    Title: Tracing with a limit

    Scenario 1:
    When: a VM sends 20 ICMP echo requests over a trace request with limit 10
    Then: Trace data appears for the ingress and the egress host,
          but only for the first 10.
    Then: when disabled and reenabled, new trace data shows up
    """
    tracerequest = VTM.get_tracerequest('ping-trace-request-limited')
    try:
        set_filters('router-000-001', 'pre_filter_001', 'post_filter_001')

        tracerequest.set_enabled(True)
        time.sleep(5)

        flowtraces = get_flow_traces(tracerequest.get_id())
        assert (len(flowtraces) == 0)

        sender = BM.get_iface_for_port('bridge-000-001', 2)
        receiver = BM.get_iface_for_port('bridge-000-002', 2)

        feed_receiver_mac(receiver)
        for i in range(0, 20):
            f2 = async_assert_that(receiver, receives('dst host 172.16.2.1 and icmp',
                                                      within_sec(10)))
            f3 = async_assert_that(sender, receives('src host 172.16.2.1 and icmp',
                                                    within_sec(10)))
            f1 = sender.ping_ipv4_addr('172.16.2.1')
            wait_on_futures([f1, f2, f3])

        time.sleep(5)

        flowtraces = get_flow_traces(tracerequest.get_id())
        assert (len(flowtraces) == 10)

        # ensure both packets were traced on both hosts
        for i in range(0, 10):
            assert(len(get_hosts(tracerequest.get_id(), flowtraces[i])) == 2)

        tracerequest.set_enabled(False)
        tracerequest.set_enabled(True)

        time.sleep(5)

        f2 = async_assert_that(receiver, receives('dst host 172.16.2.1 and icmp',
                                                  within_sec(10)))
        f3 = async_assert_that(sender, receives('src host 172.16.2.1 and icmp',
                                                within_sec(10)))
        f1 = sender.ping_ipv4_addr('172.16.2.1')
        wait_on_futures([f1, f2, f3])

        time.sleep(5)

        flowtraces = get_flow_traces(tracerequest.get_id())
        assert (len(flowtraces) == 11)
    finally:
        unset_filters('router-000-001')
        tracerequest.set_enabled(False)

