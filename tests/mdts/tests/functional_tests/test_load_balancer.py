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
from mdts.tests.utils import bindings
from mdts.tests.utils.asserts import async_assert_that, receives, should_NOT_receive, within_sec
from mdts.tests.utils import wait_on_futures

import logging
import time

LOG = logging.getLogger(__name__)
PTM = PhysicalTopologyManager('../topologies/mmm_physical_test_load_balancer.yaml')
VTM = VirtualTopologyManager('../topologies/mmm_virtual_test_load_balancer.yaml')
BM = BindingManager(PTM, VTM)

NON_STICKY_VIP = ("100.100.2.8", 10008)
STICKY_VIP = ("100.100.2.9", 10009)
SENDER = None
NUM_BACKENDS = 3


web_servers = []

binding_onehost = {
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
             {'device_name': 'bridge-000-002', 'port_id': 1,
              'host_id': 1, 'interface_id': 4}},
        {'binding':
             {'device_name': 'bridge-000-002', 'port_id': 2,
              'host_id': 1, 'interface_id': 5}},
        {'binding':
             {'device_name': 'bridge-000-002', 'port_id': 3,
              'host_id': 1, 'interface_id': 6}},
        {'binding':
             {'device_name': 'bridge-000-003', 'port_id': 1,
              'host_id': 1, 'interface_id': 7}},
        {'binding':
             {'device_name': 'bridge-000-003', 'port_id': 2,
              'host_id': 1, 'interface_id': 8}}
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
        {'binding':
             {'device_name': 'bridge-000-001', 'port_id': 3,
              'host_id': 3, 'interface_id': 1}},
        {'binding':
             {'device_name': 'bridge-000-002', 'port_id': 1,
              'host_id': 1, 'interface_id': 4}},
        {'binding':
             {'device_name': 'bridge-000-002', 'port_id': 2,
              'host_id': 2, 'interface_id': 2}},
        {'binding':
             {'device_name': 'bridge-000-002', 'port_id': 3,
              'host_id': 3, 'interface_id': 2}},
        {'binding':
             {'device_name': 'bridge-000-003', 'port_id': 1,
              'host_id': 1, 'interface_id': 7}},
        {'binding':
             {'device_name': 'bridge-000-003', 'port_id': 2,
              'host_id': 2, 'interface_id': 3}}
    ]
}

def setup():
    PTM.build()
    VTM.build()

def teardown():
    global web_servers
    # Stop web servers if any
    for ws in web_servers:
        ws.kill()
    web_servers = []

    time.sleep(2)
    PTM.destroy()
    VTM.destroy()

@attr(version="v1.3.0", slow=False)
# Commented out as a workaround for MNA-108
#@bindings(binding_onehost, binding_multihost)
@bindings(binding_multihost)
def test_multi_member_loadbalancing():
    """
    Title: Balances traffic correctly when multiple pool members are active,
           behaves differently ased on sticky source IP enabled / disabled.

    Scenario:
    When: A VM sends TCP packets to a VIP's IP address / port.
    And:  We have 3 backends of equal weight
    Then: The loadbalancer sends some traffic to each backend when sticky source IP disabled,
          all to one backend if enabled.
    """
    global STICKY_VIP
    global NON_STICKY_VIP
    global SENDER

    # With 3 backends of equal weight and 35 reqs, ~1/1m chance of not hitting all 3 backends
    # >>> 1/((2/3.0)**(35-1))
    # 970739.7373664775
    num_reqs = 35

    SENDER = BM.get_iface_for_port('bridge-000-001', 2)

    start_web_servers()

    # Make many requests to the non sticky loadbalancer IP, hits all 3 backends
    non_sticky_results = make_n_requests_to(num_reqs, NON_STICKY_VIP)
    assert(num_uniques(non_sticky_results) == 3)

    # Make many requests to the sticky loadbalancer IP, hits exactly one backend
    sticky_results = make_n_requests_to(num_reqs, STICKY_VIP)
    assert(num_uniques(sticky_results) == 1)

    # Disable (admin state down) the backend we are "stuck" to
    stuck_backend = sticky_results[0]
    stuck_pool_member = VTM.find_pool_member(stuck_backend)
    stuck_pool_member.disable()

    # We only have 2 backends now, so need less runs to ensure we hit all backends
    # >>> 1/((1/2.0)**(21-1))
    # 1048576.0
    num_reqs = 21

    # Make many requests to the non sticky loadbalancer IP, hits the 2 remaining backends
    non_sticky_results = make_n_requests_to(num_reqs, NON_STICKY_VIP)
    assert(num_uniques(non_sticky_results) == 2)
    assert(stuck_backend not in non_sticky_results)

    # Make many requests to the sticky loadbalancer IP, hits exactly one backend
    sticky_results = make_n_requests_to(num_reqs, STICKY_VIP)
    assert(num_uniques(sticky_results) == 1)
    assert(stuck_backend not in sticky_results)

    # Re-enable the pool member we disabled
    stuck_pool_member.enable()


@attr(version="v1.3.0", slow=False)
@bindings(binding_onehost, binding_multihost)
def test_disabling_topology_loadbalancing():
    """
    Title: Balances traffic correctly when loadbalancer topology elements
           are disabled. New connections to the VIP should fail when any of
           the elements are disabled. In the case of pool members, connections
           should fail when *all* pool members are disabled. In all cases, connections
           should succeed when the device is re-enabled.

    Scenario:
    When: A VM sends TCP packets to a VIP's IP address / port.
    And:  We have 3 backends of equal weight, different devices are disabled.
    Then: The loadbalancer sends traffic to a backend when the topology is fully enabled
          (admin state up) and connections fail when elements are disabled.
    """
    global STICKY_VIP
    global NON_STICKY_VIP
    global SENDER


    SENDER = BM.get_iface_for_port('bridge-000-001', 2)
    start_web_servers()

    # For each device in the L4LB topology:
    # - Disable the device, test hitting VIP fails
    # - Re-enable the device, test hitting VIP succeeds

    disable_and_assert_traffic_fails(action_pool_members)
    enable_and_assert_traffic_succeeds(action_pool_members)

    disable_and_assert_traffic_fails(action_pool)
    enable_and_assert_traffic_succeeds(action_pool)

    # Disabled due to MN-1536
    # disable_and_assert_traffic_fails(action_vips)
    enable_and_assert_traffic_succeeds(action_vips)

    # Disabled due to MN-1536
    # disable_and_assert_traffic_fails(action_loadbalancer)
    enable_and_assert_traffic_succeeds(action_loadbalancer)

@attr(version="v1.3.0", slow=False)
@bindings(binding_onehost, binding_multihost)
def test_long_connection_loadbalancing():
    """
    Title: Balances traffic correctly when topology changes during a long running connection.

    Scenario:
    When: A VM sends TCP packets to a VIP's IP address / port, long running connections.
    And:  We have 3 backends of equal weight.
    Then: When pool member disabled during connection, non-sticky connections should still succeed
          When other devices are disabled during connection, non-sticky connections should break
    """
    global STICKY_VIP
    global NON_STICKY_VIP
    global SENDER

    # Setup
    SENDER = BM.get_iface_for_port('bridge-000-001', 2)
    start_web_servers()

    # Disable all but one backend
    for backend_num in range(2, NUM_BACKENDS + 1):
        pool_member = VTM.find_pool_member(backend_ip_port(backend_num))
        getattr(pool_member, "disable")()

    assert_non_sticky_traffic_succeeds()
    assert_sticky_traffic_succeeds()

    # Disable the one remaining backend
    remaining_pool_member = VTM.find_pool_member(backend_ip_port(1))
    getattr(remaining_pool_member, "disable")()

    # Connections from the same src ip / port will be counted as the same ongoing connection

    # Non sticky traffic succeeds - connection allowed to continue
    assert_non_sticky_traffic_succeeds()
    # Sticky traffic fails - connection dropped
    assert_sticky_traffic_fails()

    # Re-enable the backend
    getattr(remaining_pool_member, "enable")()

    assert_non_sticky_traffic_succeeds()
    assert_sticky_traffic_succeeds()

    # When disabling the loadbalancer, both sticky and non sticky fail
    action_loadbalancer("disable")

    assert_non_sticky_traffic_fails()
    assert_sticky_traffic_fails()

    action_loadbalancer("enable")

################################ Helper methods

def assert_non_sticky_traffic_succeeds():
    global NON_STICKY_VIP
    assert_traffic_to(NON_STICKY_VIP, receives)

def assert_non_sticky_traffic_fails():
    global NON_STICKY_VIP
    assert_traffic_to(NON_STICKY_VIP, should_NOT_receive)

def assert_sticky_traffic_succeeds():
    global STICKY_VIP
    assert_traffic_to(STICKY_VIP, receives)

def assert_sticky_traffic_fails():
    global STICKY_VIP
    assert_traffic_to(STICKY_VIP, should_NOT_receive)

def assert_traffic_to(vip, assertion):
    backend_if = get_backend_if(1)
    source_ports = {NON_STICKY_VIP: 10108, STICKY_VIP:10109}
    source_port = source_ports[vip]
    recv_filter = 'dst host 10.0.2.1 and src port %s and tcp' % source_port

    dst_ip, dst_port = vip
    sender_router_port = VTM.get_router('router-000-001').get_port(1)
    sender_router_mac = sender_router_port.get_mn_resource().get_port_mac()
    # 41 bytes = 20 min IPv4 header + 20 min TCP header + 1 "trivial-test-udp"
    f1 = SENDER.send_tcp(sender_router_mac, dst_ip, 41,
                         src_port=source_port, dst_port=dst_port)
    f2 = async_assert_that(backend_if, assertion(recv_filter,
                                   within_sec(5)))
    wait_on_futures([f1, f2])


def disable_and_assert_traffic_fails(action_fun):
    global STICKY_VIP, NON_STICKY_VIP
    # Do action
    action_fun("disable")

    # Make one request to the non sticky loadbalancer IP, should fail
    assert_web_request_fails_to(NON_STICKY_VIP)

    # Make one request to the sticky loadbalancer IP, should fail
    assert_web_request_fails_to(STICKY_VIP)

def enable_and_assert_traffic_succeeds(action_fun):
    global STICKY_VIP, NON_STICKY_VIP
    # Do action
    action_fun("enable")

    # Make one request to the non sticky loadbalancer IP, should succeed
    assert_web_request_succeeds_to(NON_STICKY_VIP)

    # Make one request to the sticky loadbalancer IP, should succeed
    assert_web_request_succeeds_to(STICKY_VIP)

def backend_ip_port(num):
    base_port = 10001
    return '10.0.2.%s' % num, base_port + num

def get_backend_if(num):
    return BM.get_iface_for_port('bridge-000-002', num)

def action_loadbalancer(fun_name):
    first_pool_member = VTM.find_pool_member(backend_ip_port(1))
    lb = first_pool_member._pool._load_balancer
    getattr(lb, fun_name)()

def action_vips(fun_name):
    global STICKY_VIP
    global NON_STICKY_VIP
    for current_vip in [STICKY_VIP, NON_STICKY_VIP]:
        vip = VTM.find_vip(current_vip)
        getattr(vip, fun_name)()

def action_pool(fun_name):
    first_pool_member = VTM.find_pool_member(backend_ip_port(1))
    pool = first_pool_member._pool
    getattr(pool, fun_name)()

def action_pool_members(fun_name):
    global NUM_BACKENDS
    for backend_num in range(1, NUM_BACKENDS + 1):
        pool_member = VTM.find_pool_member(backend_ip_port(backend_num))
        getattr(pool_member, fun_name)()

def start_web_servers():
    global web_servers
    global NUM_BACKENDS

    # Start web servers, send gratuitous arp replies
    # then wait for arp replies to propagate and web servers to start
    router_port = VTM.get_router('router-000-001').get_port(2)
    router_mac = router_port.get_mn_resource().get_port_mac()

    for backend_num in range(1, NUM_BACKENDS + 1):
        backend_ip, backend_port = backend_ip_port(backend_num)
        backend_if = get_backend_if(backend_num)
        # When we switch bindings, router's ARP table will still keep
        # the IP <> Mac mapping from previous test, so we send gratuitous
        # ARP from receiver to avoid this issue
        f = backend_if.send_arp_reply(backend_if.get_mac_addr(), router_mac,
                           backend_ip, '10.0.2.254')
        wait_on_futures([f])
        ws = backend_if.start_web_server(backend_port)
        web_servers.append(ws)

    time.sleep(2)

def assert_web_request_succeeds_to(dest):
    global SENDER
    result_future = SENDER.make_web_request_to(dest, timeout_secs=5)
    assert result_future.result(timeout=10) is not None

def assert_web_request_fails_to(dest):
    global SENDER
    result_future = SENDER.make_web_request_to(dest, timeout_secs=5)
    assert result_future.exception(timeout=10) is not None

def make_n_requests_to(num_reqs, dest):
    global SENDER
    results = []
    for x in range(0, num_reqs):
        res = SENDER.make_web_request_get_backend(dest)
        results.append(res)
    return results

def num_uniques(l):
    uniques = set()
    for item in l:
        uniques.add(item)
    return len(uniques)

