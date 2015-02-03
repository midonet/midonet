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
from mdts.lib.failure.no_failure import NoFailure
from mdts.lib.failure.netif_failure import NetifFailure
from mdts.lib.failure.pkt_failure import PktFailure
from mdts.lib.failure.namespaces import *

from mdts.tests.utils.asserts import *
from mdts.tests.utils import *
from nose.plugins.attrib import attr

from hamcrest import *
from nose.tools import nottest

import logging
import time
import pdb
import re
import subprocess


LOG = logging.getLogger(__name__)

PTM = PhysicalTopologyManager('../topologies/mmm_physical_test_bgp.yaml')
VTM = VirtualTopologyManager('../topologies/mmm_virtual_test_bgp.yaml')
BM = BindingManager(PTM, VTM)


binding_uplink_1 = {
    'description': 'connected to uplink #1',
    'bindings': [
        {'binding':
             {'device_name': 'bridge-000-001', 'port_id': 2,
              'host_id': 1, 'interface_id': 1}},
        {'binding':
             {'device_name': 'router-000-001', 'port_id': 2,
              'host_id': 1, 'interface_id': 2}},
        {'binding':
             {'device_name': 'router-000-001', 'port_id': 3,
              'host_id': 2, 'interface_id': 2}},
        ]
    }

binding_uplink_2 = {
    'description': 'connected to uplink #2',
    'bindings': [
        {'binding':
             {'device_name': 'bridge-000-001', 'port_id': 2,
              'host_id': 2, 'interface_id': 1}},
        {'binding':
             {'device_name': 'router-000-001', 'port_id': 2,
              'host_id': 1, 'interface_id': 2}},
        {'binding':
             {'device_name': 'router-000-001', 'port_id': 3,
              'host_id': 2, 'interface_id': 2}},
        ]
    }

binding_indirect = {
    'description': 'not connected to uplink',
    'bindings': [
        {'binding':
             {'device_name': 'bridge-000-001', 'port_id': 2,
              'host_id': 3, 'interface_id': 1}},
        {'binding':
             {'device_name': 'router-000-001', 'port_id': 2,
              'host_id': 1, 'interface_id': 2}},
        {'binding':
             {'device_name': 'router-000-001', 'port_id': 3,
              'host_id': 2, 'interface_id': 2}},
        ]
    }

binding_snat_1 = {
    'description': 'one connected to uplink #1 and another not connected',
    'bindings': [
        {'binding':
             {'device_name': 'bridge-000-001', 'port_id': 2,
              'host_id': 1, 'interface_id': 1}},
        {'binding':
             {'device_name': 'bridge-000-001', 'port_id': 3,
              'host_id': 2, 'interface_id': 1}},
        {'binding':
             {'device_name': 'router-000-001', 'port_id': 2,
              'host_id': 1, 'interface_id': 2}},
        ]
    }

binding_snat_2 = {
    'description': 'one not connected and another connected to uplink #1',
    'bindings': [
        {'binding':
             {'device_name': 'bridge-000-001', 'port_id': 2,
              'host_id': 2, 'interface_id': 1}},
        {'binding':
             {'device_name': 'bridge-000-001', 'port_id': 3,
              'host_id': 1, 'interface_id': 1}},
        {'binding':
             {'device_name': 'router-000-001', 'port_id': 2,
              'host_id': 1, 'interface_id': 2}},
        ]
    }

binding_snat_3 = {
    'description': 'two not connected to uplink',
    'bindings': [
        {'binding':
             {'device_name': 'bridge-000-001', 'port_id': 2,
              'host_id': 2, 'interface_id': 1}},
        {'binding':
             {'device_name': 'bridge-000-001', 'port_id': 3,
              'host_id': 3, 'interface_id': 1}},
        {'binding':
             {'device_name': 'router-000-001', 'port_id': 2,
              'host_id': 1, 'interface_id': 2}},
        ]
    }

def setup():
    PTM.build()
    VTM.build()

def teardown():
    time.sleep(2)
    PTM.destroy()
    VTM.destroy()

# Even though quickly copied from test_nat_router for now, utilities below
# should probably be moved to somewhere shared among tests
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

def add_bgp(port, bgp, wait=5):
    b = port._mn_resource.add_bgp()\
        .local_as(bgp['localAS'])\
        .peer_as(bgp['peerAS'])\
        .peer_addr(bgp['peerAddr'])\
        .create()
    for route in bgp['adRoute']:
        b.add_ad_route()\
            .nw_prefix(route['nwPrefix'])\
            .nw_prefix_length(route['prefixLength'])\
            .create()

    if wait > 0:
        time.sleep(wait)

    return b

def clear_bgp(port, wait=0):
    for b in port._mn_resource.get_bgps():
        b.delete()
    if wait > 0:
        time.sleep(wait)


# There are two uplinks available in MMM, one of which is from ns008(eth1)
# to ns000(eth0) and another of which is from ns009(eth1) to ns000(eth1).
# The BGP #1 is establed over the first and BGP #2 over the second.
def add_bgp_1(routes):
    p = VTM.get_router('router-000-001').get_port(2)
    add_bgp(p, {'localAS': 64513,
                'peerAS': 64512,
                'peerAddr': '10.1.0.240',
                'adRoute': routes})
    return p

def add_bgp_2(routes):
    p = VTM.get_router('router-000-001').get_port(3)
    add_bgp(p, {'localAS': 64514,
                'peerAS': 64512,
                'peerAddr': '10.2.0.240',
                'adRoute': routes})
    return p

# routes BGP advertises:
route_direct = [{'nwPrefix': '172.16.0.0', 'prefixLength': 16}]
route_snat = [{'nwPrefix': '100.0.0.0', 'prefixLength': 16}]

# 1.1.1.1 is assigned to lo in ns000 emulating a public IP address
def ping_inet(count=3, interval=1, retry=True, retry_count=2, port=2):
    sender = BM.get_iface_for_port('bridge-000-001', port)
    try:
        f1 = sender.ping_ipv4_addr('1.1.1.1', interval=interval, count=count)
        wait_on_futures([f1])
    except subprocess.CalledProcessError as e:
        # FIXME: Further Investivagion Needed
        # The first several ping attempts occasionally fail, so try it again.
        # What does it really mean? Some timing issue or even bug in midolman?
        if retry and retry_count > 0:
            ping_inet(count, interval, retry, retry_count - 1, port)
        else:
            raise AssertionError(e.cmd, e.returncode)

@attr(version="v1.2.0", slow=False)
@bindings(binding_uplink_1, binding_uplink_2, binding_indirect)
def test_icmp_multi_add_uplink_1():
    """
    Title: configure BGP to establish multiple uplinks

    Scenario 1:
    Given: one uplink
    When: enable one BGP on it
    Then: ICMP echo RR should work to a psudo public IP address

    Scenario 2:
    Given: another uplink
    When: enable another BGP on it
    Then: ICMP echo RR should work to a psudo public IP address

    """
    p1 = add_bgp_1(route_direct)
    ping_inet() # BGP #1 is working

    p2 = add_bgp_2(route_direct)
    ping_inet() # BGP #1 and #2 are working

    clear_bgp(p1)
    clear_bgp(p2)

@attr(version="v1.2.0", slow=False)
@bindings(binding_uplink_1, binding_uplink_2, binding_indirect)
def test_icmp_multi_add_uplink_2():
    """
    Title: configure BGP to establish multiple BGP links

    * Basically the same as above, enables multiple BGP
    * in reverse order

    """
    p2 = add_bgp_2(route_direct)
    ping_inet() # BGP #2 is working

    p1 = add_bgp_1(route_direct)
    ping_inet() # BGP #1 and #2 are working

    clear_bgp(p1)
    clear_bgp(p2)

@attr(version="v1.2.0", slow=False)
@bindings(binding_uplink_1, binding_uplink_2, binding_indirect)
def test_icmp_remove_uplink_1():
    """
    Title: Remove one BGP from multiple BGP links

    Scenario 1:
    Given: multiple uplinks with BGP enabled
    When: disable one of BGP uplinks
    Then: ICMP echo RR should work to a pseudo public IP address

    """
    (p1, p2) = (add_bgp_1(route_direct), add_bgp_2(route_direct))
    ping_inet() # BGP #1 and #2 are working

    clear_bgp(p1, 20)
    ping_inet() # only BGP #2 is working

    clear_bgp(p2)

@attr(version="v1.2.0", slow=False)
@bindings(binding_uplink_1, binding_uplink_2, binding_indirect)
def test_icmp_remove_uplink_2():
    """
    Title: Remove one BGP from multiple BGP links

    * Basically the same as above, disable the other BGP

    """
    (p1, p2) = (add_bgp_1(route_direct), add_bgp_2(route_direct))
    ping_inet() # BGP #1 and #2 are working

    clear_bgp(p2, 20)
    ping_inet() # only BGP #1 is working

    clear_bgp(p1)

@attr(version="v1.2.0", slow=True)
@bindings(binding_uplink_1, binding_uplink_2, binding_indirect)
def test_icmp_failback():
    """
    Title: BGP failover/failback

    Scenario 1:
    Given: multiple uplinks
    When: enable BGP on both of them
    Then: ICMP echo RR should work to a pseudo public IP address

    Scenario 2:
    Given:
    When: inject failure into one of BGP uplinks (failover)
    Then: ICMP echo RR should work to a pseudo public IP address

    Scenario 3:
    Given:
    When: eject failure (failback)
    Then: ICMP echo RR should work to a psudo public IP address

    Scenario 4:
    Given:
    When: inject failure into another of BGP uplinks (failover)
    Then: ICMP echo RR should work to a pseudo public IP address

    Scenario 5:
    Given:
    When: eject failure (failback)
    Then: ICMP echo RR should work to a pseudo public IP address

    """
    (p1, p2) = (add_bgp_1(route_direct), add_bgp_2(route_direct))

    ping_inet() # BGP #1 and #2 are working

    failure = PktFailure(NS_BGP_PEER_1, 'eth0', 35)
    failure.inject()
    try:
        ping_inet() # BGP #1 is lost
    finally:
        failure.eject()

    ping_inet() # BGP #1 is back

    failure = PktFailure(NS_BGP_PEER_1, 'eth1', 35)
    failure.inject()
    try:
        ping_inet() # BGP #2 is lost
    finally:
        failure.eject()

    ping_inet()  # BGP #2 is back

    clear_bgp(p1)
    clear_bgp(p2)

@attr(version="v1.2.0", slow=True)
@failures(NoFailure(),
          NetifFailure(NS_CASSANDRA_1, 'eth0', 30),
          NoFailure(),
          NetifFailure(NS_CASSANDRA_2, 'eth0', 30),
          NetifFailure(NS_CASSANDRA_3, 'eth0', 30),
          NoFailure())
@bindings(binding_uplink_1, binding_uplink_2, binding_indirect)
def test_snat_1():
    """
    Title: Emulate Cassandra failure

    Scenario 1:
    Given: one uplink
    When: inject network interface failure in ONE of Cassandra nodes
    Then: ICMP echo RR should work

    """
    p1 = add_bgp_1(route_snat)

    set_filters('router-000-001', 'pre_filter_snat_ip', 'post_filter_snat_ip')

    try:
        # requires all 10 trials work and each of which has one pair of
        # echo/reply work at least in 5 trials (i.e. about 10 second)
        # failover possibly takes about 5-6 seconds at most
        for i in range(0, 10):
            # BGP #1 is working
            ping_inet(count=1, retry_count=5)
    finally:
        unset_filters('router-000-001')

    clear_bgp(p1)

# FIXME: https://midobugs.atlassian.net/browse/MN-1759
@attr(version="v1.2.0", slow=True, flaky=True)
@bindings(binding_uplink_1, binding_uplink_2, binding_indirect)
def test_snat_2():
    """
    Title: Emulate Cassandra failure

           basically the same one as test_snat_1, but the difference is
           how and when to inject failures.

    """
    p1 = add_bgp_1(route_snat)

    set_filters('router-000-001', 'pre_filter_snat_ip', 'post_filter_snat_ip')

    try:
        for failure in (NoFailure(),
                        NetifFailure(NS_CASSANDRA_1, 'eth0', 30),
                        NoFailure(),
                        NetifFailure(NS_CASSANDRA_2, 'eth0', 30),
                        NetifFailure(NS_CASSANDRA_3, 'eth0', 30),
                        NoFailure()):
            failure.inject()
            try:
                for i in range(0, 10):
                    ping_inet(count=1, retry_count=5)
            finally:
                failure.eject()
    finally:
        unset_filters('router-000-001')

    clear_bgp(p1)

@attr(version="v1.2.0", slow=True)
@bindings(binding_snat_1, binding_snat_2, binding_snat_3)
def test_mn_1172():
    """
    Title: Simultaneous ICMP SNAT

    Scenario 1:
    Given: one uplink
    When: enable one BGP on it
    Then: ICMP echo RR should work from multiple VMs

    """
    p1 = add_bgp_1(route_snat)

    set_filters('router-000-001', 'pre_filter_snat_ip', 'post_filter_snat_ip')

    try:
        ping_inet(port=2)
        ping_inet(port=3)
        ping_inet(port=2)
    finally:
        unset_filters('router-000-001')

    clear_bgp(p1)
