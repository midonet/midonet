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
from mdts.services import service

from mdts.tests.utils.utils import bindings
from mdts.tests.utils.utils import wait_on_futures

from mdts.tests.utils.asserts import *
from mdts.tests.utils import *


from nose.plugins.attrib import attr

from hamcrest import *
from nose.tools import nottest, with_setup

import logging
import time
import pdb
import re
import subprocess

LOG = logging.getLogger(__name__)

PTM = PhysicalTopologyManager('../topologies/mmm_physical_test_bgp.yaml')
VTM = VirtualTopologyManager('../topologies/mmm_virtual_test_bgp.yaml')
BM = BindingManager(PTM, VTM)


binding_unisession = {
    'description': 'vm not connected to uplink',
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

binding_snat = {
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


def add_bgp(bgp_peers, networks):
    router = VTM.get_router('router-000-001')

    localAs = 64513
    peers = []
    for bgp_peer in bgp_peers:
        peer = add_bgp_peer(bgp_peer, networks)
        peers.append(peer)

    for bgp_peer in bgp_peers:
        await_default_route(router, bgp_peer['port'], bgp_peer['peerAddr'])
        await_internal_route_exported(localAs, bgp_peer['peerAs'])

    return peers[0] if len(peers) == 1 else peers


def add_bgp_peer(peer, networks):
    router = VTM.get_router('router-000-001')
    port = router.get_port(peer['port'])
    localAs = 64513
    peer = port._mn_resource\
        .add_bgp()\
        .local_as(localAs)\
        .peer_as(peer['peerAs'])\
        .peer_addr(peer['peerAddr'])\
        .create()
    for network in networks:
        peer.add_ad_route()\
            .nw_prefix(network['nwPrefix'])\
            .nw_prefix_length(network['prefixLength'])\
            .create()

    return peer


def clear_bgp():
    router = VTM.get_router('router-000-001')
    for port in router._mn_resource.get_ports():
        for bgp_peer in port.get_bgps():
            clear_bgp_peer(bgp_peer)


def clear_bgp_peer(bgp_peer, wait=0):
    bgp_peer.delete()
    if wait > 0:
        time.sleep(wait)


def await_bgp_peer(bgp_peer_def, present=True):
    # Check that the routes are removed before issuing pings
    await_default_route(VTM.get_router('router-000-001'),
                        bgp_peer_def['port'],
                        bgp_peer_def['peerAddr'],
                        present=present)
    await_internal_route_exported(64513,
                                  bgp_peer_def['peerAs'],
                                  present=present)


def await_default_route(router, port, peerAddr, present=True):
    port_id = router.get_port(port)._mn_resource.get_id()
    router_id = router._mn_resource.get_id()
    timeout = 60
    while timeout > 0:
        routes = BM._api.get_router_routes(router_id)
        for r in routes:
            condition = (r.get_next_hop_port() == port_id and
                         r.get_dst_network_length() == 0 and
                         r.get_dst_network_addr() == '0.0.0.0' and
                         r.get_next_hop_gateway() == peerAddr)
            if condition == present:
                return
        time.sleep(1)
        timeout -= 1
    raise Exception("Timed out while waiting for BGP to be set up on router "
                    "{0} port {1} to peer {2}".format(router_id, port_id, peerAddr))


def await_internal_route_exported(localAs, peerAs, present=True):
    quagga = service.get_container_by_hostname('quagga0')
    timeout = 60
    while timeout > 0:
        output_stream, exec_id = quagga.exec_command(
            "sh -c \"vtysh -c 'show ip bgp' | grep %s | grep %s\"" % (
                localAs, peerAs), stream=True)
        exit_code = quagga.check_exit_status(exec_id, output_stream)
        if present == (exit_code == 0):
            # The route is present (or not)
            return
        time.sleep(2)
        timeout -= 2
    raise Exception("Timed out while waiting for quagga0 to learn the internal "
                    "network through AS %s " %(peerAs))

# routes BGP advertises:
route_direct = [{'nwPrefix': '172.16.0.0', 'prefixLength': 16}]
route_snat = [{'nwPrefix': '100.0.0.0', 'prefixLength': 16}]

# peers available:
uplink1_session1 = {'port': 2, 'peerAddr': '10.1.0.240', 'peerAs': 64511}
uplink2_session1 = {'port': 3, 'peerAddr': '10.2.0.240', 'peerAs': 64512}


# 1.1.1.1 is assigned to lo in ns000 emulating a public IP address
def ping_to_inet(count=5, interval=1, port=2, retries=5):
    try:
        sender = BM.get_iface_for_port('bridge-000-001', port)
        f1 = sender.ping_ipv4_addr('1.1.1.1',
                                   interval=interval,
                                   count=count)
        wait_on_futures([f1])
        output_stream, exec_id = f1.result()
        exit_status = sender.compute_host.check_exit_status(exec_id,
                                                            output_stream,
                                                            timeout=20)

        assert_that(exit_status, equal_to(0), "Ping did not return any data")
    except:
        if retries == 0:
            raise RuntimeError("Ping did not return any data and returned -1")
        LOG.debug("BGP: failed ping to inet... (%d retries left)" % retries)
        ping_to_inet(count, interval, port, retries-1)

@attr(version="v1.2.0")
@bindings(binding_unisession)
@with_setup(None, clear_bgp)
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
    add_bgp([uplink1_session1], route_direct)
    ping_to_inet() # BGP #1 is working

    add_bgp([uplink2_session1], route_direct)
    ping_to_inet() # BGP #1 and #2 working


@attr(version="v1.2.0")
@bindings(binding_unisession)
@with_setup(None, clear_bgp)
def test_icmp_remove_uplink_1():
    """
    Title: Remove one BGP from multiple BGP links

    Scenario 1:
    Given: multiple uplinks with BGP enabled
    When: disable one of BGP uplinks
    Then: ICMP echo RR should work to a pseudo public IP address

    """
    (p1, p2) = (add_bgp([uplink1_session1], route_direct),
                add_bgp([uplink2_session1], route_direct))
    ping_to_inet() # BGP #1 and #2 are working

    clear_bgp_peer(p1, 5)
    await_bgp_peer(uplink1_session1, present=False)

    ping_to_inet() # only BGP #2 is working

@attr(version="v1.2.0")
@bindings(binding_unisession)
@with_setup(None, clear_bgp)
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
    add_bgp([uplink1_session1], route_direct)
    add_bgp([uplink2_session1], route_direct)

    ping_to_inet() # BGP #1 and #2 are working

    failure = PktFailure('quagga1', 'bgp1', 5)

    failure.inject()
    await_bgp_peer(uplink1_session1, present=False)
    try:
        ping_to_inet() # BGP #1 is lost but continues to work
    finally:
        failure.eject()

    await_internal_route_exported(64513, 64511)
    await_bgp_peer(uplink1_session1, present=True)
    ping_to_inet() # BGP #1 is back

    failure = PktFailure('quagga2', 'bgp1', 5)
    failure.inject()
    await_bgp_peer(uplink2_session1, present=False)
    try:
        ping_to_inet() # BGP #2 is lost but continues to work
    finally:
        failure.eject()

    await_bgp_peer(uplink2_session1, present=True)
    ping_to_inet()  # BGP #2 is back

@attr(version="v1.2.0")
@bindings(binding_unisession)
@with_setup(None, clear_bgp)
def test_snat():
    """
    Title: SNAT test with one uplink

    Scenario 1:
    Given: one uplink
    When: adding snat chains
    Then: ICMP echo RR should work

    """
    add_bgp([uplink1_session1], route_snat)

    set_filters('router-000-001', 'pre_filter_snat_ip', 'post_filter_snat_ip')

    try:
        # requires all 10 trials work and each of which has one pair of
        # echo/reply work at least in 5 trials (i.e. about 10 second)
        # failover possibly takes about 5-6 seconds at most
        for i in range(0, 10):
            # BGP #1 is working
            ping_to_inet()
    finally:
        unset_filters('router-000-001')


@attr(version="v1.2.0")
@bindings(binding_snat)
@with_setup(None, clear_bgp)
def test_mn_1172():
    """
    Title: Simultaneous ICMP SNAT

    Scenario 1:
    Given: one uplink
    When: enable one BGP on it
    Then: ICMP echo RR should work from multiple VMs

    """
    add_bgp([uplink1_session1], route_snat)

    set_filters('router-000-001', 'pre_filter_snat_ip', 'post_filter_snat_ip')

    try:
        ping_to_inet(port=2)
        ping_to_inet(port=3)
        ping_to_inet(port=2)
    finally:
        unset_filters('router-000-001')
