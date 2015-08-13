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
from mdts.lib.failure.pkt_failure import PktFailure

from mdts.tests.utils.utils import bindings
from mdts.tests.utils.utils import wait_on_futures

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

def add_bgp(router, bgp):
    router.set_asn(bgp['localAs'])
    peer = router.add_bgp_peer(bgp['peerAs'], bgp['peerAddr'])
    for network in bgp['networks']:
        router.add_bgp_network(network['nwPrefix'], network['prefixLength'])

    try:
        await_default_route(router, bgp['port'])
    except Exception as e:
        clear_bgp()
        raise e
    return peer

def add_bgp_1(networks):
    return add_bgp(VTM.get_router('router-000-001'), {
        'port': 2,
        'localAs': 64513,
        'peerAs': 64512,
        'peerAddr': '10.1.0.240',
        'networks': networks
    })

def add_bgp_2(networks):
    return add_bgp(VTM.get_router('router-000-001'), {
        'port': 3,
        'localAs': 64513,
        'peerAs': 64512,
        'peerAddr': '10.2.0.240',
        'networks': networks
    })

def clear_bgp():
    router = VTM.get_router('router-000-001')
    router.clear_bgp_networks()
    router.clear_bgp_peers()
    router.clear_asn()

def clear_bgp_peer(peer, wait=0):
    peer.delete()
    if wait > 0:
        time.sleep(wait)

def await_default_route(router, port):
    port_id = router.get_port(port)._mn_resource.get_id()
    router_id = router._mn_resource.get_id()
    timeout = 20
    while timeout > 0:
        routes = BM._api.get_router_routes(router_id)
        for r in routes:
            if r.get_next_hop_port() == port_id and \
                r.get_dst_network_length() == 0 and \
                r.get_dst_network_addr() == '0.0.0.0':
                return
        time.sleep(1)
        timeout -= 1
    raise Exception("Timed out while waiting for BGP to be set up on router "
                    "{0} port {1}".format(router_id, port_id))

# routes BGP advertises:
route_direct = [{'nwPrefix': '172.16.0.0', 'prefixLength': 16}]
route_snat = [{'nwPrefix': '100.0.0.0', 'prefixLength': 16}]

# 1.1.1.1 is assigned to lo in ns000 emulating a public IP address
def ping_inet(count=10, interval=2, port=2, retries=10):
    try:
        sender = BM.get_iface_for_port('bridge-000-001', port)
        f1 = sender.ping_ipv4_addr('1.1.1.1',
                                   interval=interval,
                                   count=count)
        wait_on_futures([f1])
        output_stream, exec_id = f1.result()
        exit_status = sender.compute_host.check_exit_status(exec_id,
                                                            output_stream,
                                                            timeout=60)

        assert_that(exit_status, equal_to(0), "Ping did not return any data")
    except:
        if retries == 0:
            assert_that(-1, equal_to(0), "Ping did not return any data")

        ping_inet(count, interval, port, retries-1)

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
    add_bgp_1(route_direct)
    ping_inet() # BGP #1 is working

    add_bgp_2(route_direct)
    ping_inet() # BGP #1 and #2 working

    clear_bgp()

@attr(version="v1.2.0", slow=False)
@bindings(binding_uplink_1, binding_uplink_2, binding_indirect)
def test_icmp_multi_add_uplink_2():
    """
    Title: configure BGP to establish multiple BGP links

    * Basically the same as above, enables multiple BGP
    * in reverse order

    """
    add_bgp_1(route_direct)
    ping_inet() # BGP #2 is working

    add_bgp_2(route_direct)
    ping_inet() # BGP #1 and #2 are working

    clear_bgp()

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

    clear_bgp_peer(p1, 20)
    ping_inet() # only BGP #2 is working

    clear_bgp()

@attr(version="v1.2.0", slow=False)
@bindings(binding_uplink_1, binding_uplink_2, binding_indirect)
def test_icmp_remove_uplink_2():
    """
    Title: Remove one BGP from multiple BGP links

    * Basically the same as above, disable the other BGP

    """
    (p1, p2) = (add_bgp_1(route_direct), add_bgp_2(route_direct))
    ping_inet() # BGP #1 and #2 are working

    clear_bgp_peer(p2, 20)
    ping_inet() # only BGP #1 is working

    clear_bgp()

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
    add_bgp_1(route_direct)
    add_bgp_2(route_direct)

    ping_inet() # BGP #1 and #2 are working

    failure = PktFailure('quagga', 'bgp0', 15)

    failure.inject()
    try:
        ping_inet() # BGP #1 is lost
    finally:
        failure.eject()

    ping_inet() # BGP #1 is back

    failure = PktFailure('quagga', 'bgp1', 15)
    failure.inject()
    try:
        ping_inet() # BGP #2 is lost
    finally:
        failure.eject()

    ping_inet()  # BGP #2 is back

    clear_bgp()

@attr(version="v1.2.0", slow=True)
@bindings(binding_uplink_1, binding_uplink_2, binding_indirect)
def test_snat():
    """
    Title: Emulate Cassandra failure

    Scenario 1:
    Given: one uplink
    When: inject network interface failure in ONE of Cassandra nodes
    Then: ICMP echo RR should work

    """
    add_bgp_1(route_snat)

    set_filters('router-000-001', 'pre_filter_snat_ip', 'post_filter_snat_ip')

    try:
        # requires all 10 trials work and each of which has one pair of
        # echo/reply work at least in 5 trials (i.e. about 10 second)
        # failover possibly takes about 5-6 seconds at most
        for i in range(0, 10):
            # BGP #1 is working
            ping_inet(count=1)
    finally:
        unset_filters('router-000-001')
        clear_bgp()


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
    add_bgp_1(route_snat)

    set_filters('router-000-001', 'pre_filter_snat_ip', 'post_filter_snat_ip')

    try:
        ping_inet(port=2)
        ping_inet(port=3)
        ping_inet(port=2)
    finally:
        unset_filters('router-000-001')

    clear_bgp()
