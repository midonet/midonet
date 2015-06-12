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
from hamcrest import equal_to, is_not
from nose.plugins.attrib import attr
from nose.tools import with_setup, nottest

from mdts.lib.binding_manager import BindingManager
from mdts.lib.physical_topology_manager import PhysicalTopologyManager
from mdts.lib.virtual_topology_manager import VirtualTopologyManager
from mdts.tests.utils.utils import bindings
from mdts.tests.utils.asserts import async_assert_that, receives, should_NOT_receive, within_sec
from mdts.tests.utils.utils import wait_on_futures

import logging
import time

LOG = logging.getLogger(__name__)
PTM = PhysicalTopologyManager('../topologies/mmm_physical_test_load_balancer.yaml')
VTM = VirtualTopologyManager('../topologies/mmm_virtual_test_load_balancer.yaml')
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
              'host_id': 1, 'interface_id': 7}}
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
              'host_id': 1, 'interface_id': 7}}
    ]
}

DST_PORT = 10000

NON_STICKY_VIP_WEIGHTED = "100.100.1.8"
STICKY_VIP_WEIGHTED = "100.100.1.9"

NON_STICKY_VIP = "100.100.2.8"
STICKY_VIP = "100.100.2.9"
NUM_BACKENDS = 3

SERVERS = dict()


################################ Helper methods

def disable_and_assert_traffic_fails(sender, action_fun):
    # Do action
    action_fun("disable")

    # Make one request to the non sticky loadbalancer IP, should fail
    assert_request_fails_to(sender, NON_STICKY_VIP)

    # Make one request to the sticky loadbalancer IP, should fail
    assert_request_fails_to(sender, STICKY_VIP)


def enable_and_assert_traffic_succeeds(sender, action_fun):
    # Do action
    action_fun("enable")

    # Make one request to the non sticky loadbalancer IP, should succeed
    assert_request_succeeds_to(sender, NON_STICKY_VIP)

    # Make one request to the sticky loadbalancer IP, should succeed
    assert_request_succeeds_to(sender, STICKY_VIP)


def backend_ip_port(num):
    return '10.0.2.%s' % num, DST_PORT


def get_backend_if(num):
    return BM.get_iface_for_port('bridge-000-002', num)


def action_loadbalancer(fun_name):
    first_pool_member = VTM.find_pool_member(backend_ip_port(1))
    lb = first_pool_member._pool._load_balancer
    getattr(lb, fun_name)()


def action_vips(fun_name):
    for current_vip in [STICKY_VIP, NON_STICKY_VIP]:
        vip = VTM.find_vip((current_vip, DST_PORT))
        getattr(vip, fun_name)()


def action_pool(fun_name):
    first_pool_member = VTM.find_pool_member(backend_ip_port(1))
    pool = first_pool_member._pool
    getattr(pool, fun_name)()


def action_pool_members(fun_name):
    for backend_num in range(1, NUM_BACKENDS + 1):
        pool_member = VTM.find_pool_member(backend_ip_port(backend_num))
        getattr(pool_member, fun_name)()


def start_server(backend_num):
    global SERVERS

    backend_ip, backend_port = backend_ip_port(backend_num)
    backend_if = get_backend_if(backend_num)
    f = backend_if.execute("sh -c \"while true; do "
                           "sh -c 'echo %s | nc -l %s %s'; "
                           "done\"" % (
                               backend_ip, backend_ip, backend_port))
    output_stream, exec_id = f.result()

    SERVERS.setdefault(backend_num, backend_if)

def stop_server(backend_num):
    global SERVERS
    backend_if = SERVERS[backend_num]
    pid = backend_if.execute(
        "sh -c \"netstat -ntlp | "
        "grep LISTEN | "
        "xargs -n 1 | "
        "cut -d/ -f1 | tail -n1\"",
        sync=True)
    # Kill the parent process (the sh process that started the server)
    ps_format = "%p %r %c"
    ppid = backend_if.execute(
        "sh -c \"ps -x -o '%s' | "
        "grep %s | xargs | cut -d' ' -f2 | sort -u \"" % (ps_format,
                                                          pid),
        sync=True)
    backend_if.execute("kill -9 -- -%s" % ppid, sync=True)
    del SERVERS[backend_num]

def start_servers():
    for backend_num in range(1, NUM_BACKENDS + 1):
        start_server(backend_num)


def stop_servers():
    global SERVERS
    for backend_num in range(1, NUM_BACKENDS + 1):
        stop_server(backend_num)
    SERVERS = dict()

def make_request_to(sender, dest, timeout=10, src_port=None):
        cmd_line = 'nc %s %s %d' % (
            '-p %d' % src_port if src_port is not None else '',
            dest,
            DST_PORT
        )
        return sender.execute(cmd_line, timeout, sync=True)

def assert_request_succeeds_to(sender, dest, timeout=10, src_port=None):
    result = make_request_to(sender, dest, timeout, src_port)
    assert_that(result, is_not(equal_to('')))


def assert_request_fails_to(sender, dest, timeout=10, src_port=None):
    result = make_request_to(sender, dest, timeout, src_port)
    assert_that(result, equal_to(''))


def make_n_requests_to(sender, num_reqs, dest, timeout=10, src_port=None):
    results = []
    for x in range(0, num_reqs):
        result = make_request_to(sender, dest, timeout, src_port)
        results.append(result)
    return results


def num_uniques(list_results):
    uniques = set()
    for item in list_results:
        uniques.add(item)
    return len(uniques)

# TODO: these tests does not cover different backend weights
# although the interfaces and bindings are specified in the topology yamls.

@attr(version="v1.3.0", slow=False)
@bindings(binding_onehost, binding_multihost)
@with_setup(start_servers, stop_servers)
def test_multi_member_loadbalancing():
    """
    Title: Balances traffic correctly when multiple pool members are active,
           behaves differently based on sticky source IP enabled / disabled.

    Scenario:
    When: A VM sends TCP packets to a VIP's IP address / port.
    And:  We have 3 backends of equal weight
    Then: The loadbalancer sends some traffic to each backend when sticky source IP disabled,
          all to one backend if enabled.
    """
    # With 3 backends of equal weight and 35 reqs, ~1/1m chance of not hitting all 3 backends
    # >>> 1/((2/3.0)**(35-1))
    # 970739.7373664775
    num_reqs = 35

    sender = BM.get_iface_for_port('bridge-000-003', 1)


    # Make many requests to the non sticky loadbalancer IP, hits all 3 backends
    non_sticky_results = make_n_requests_to(sender, num_reqs, NON_STICKY_VIP)
    assert(num_uniques(non_sticky_results) == 3)

    # Make many requests to the sticky loadbalancer IP, hits exactly one backend
    sticky_results = make_n_requests_to(sender, num_reqs, STICKY_VIP)
    assert(num_uniques(sticky_results) == 1)

    # Disable (admin state down) the backend we are "stuck" to
    stuck_backend = sticky_results[0]
    stuck_pool_member = VTM.find_pool_member((stuck_backend, DST_PORT))
    stuck_pool_member.disable()

    # We only have 2 backends now, so need less runs to ensure we hit all backends
    # >>> 1/((1/2.0)**(21-1))
    # 1048576.0
    num_reqs = 21

    # Make many requests to the non sticky loadbalancer IP, hits the 2 remaining backends
    non_sticky_results = make_n_requests_to(sender, num_reqs, NON_STICKY_VIP)
    assert(num_uniques(non_sticky_results) == 2)
    assert(stuck_backend not in non_sticky_results)

    # Make many requests to the sticky loadbalancer IP, hits exactly one backend
    sticky_results = make_n_requests_to(sender, num_reqs, STICKY_VIP)
    assert(num_uniques(sticky_results) == 1)
    assert(stuck_backend not in sticky_results)

    # Re-enable the pool member we disabled
    stuck_pool_member.enable()


@attr(version="v1.3.0", slow=False)
@bindings(binding_onehost, binding_multihost)
@with_setup(start_servers, stop_servers)
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

    sender = BM.get_iface_for_port('bridge-000-003', 1)

    # For each device in the L4LB topology:
    # - Disable the device, test hitting VIP fails
    # - Re-enable the device, test hitting VIP succeeds
    disable_and_assert_traffic_fails(sender, action_pool_members)
    enable_and_assert_traffic_succeeds(sender, action_pool_members)

    disable_and_assert_traffic_fails(sender, action_pool)
    enable_and_assert_traffic_succeeds(sender, action_pool)

    # Disabled due to MN-1536
    disable_and_assert_traffic_fails(sender, action_vips)
    enable_and_assert_traffic_succeeds(sender, action_vips)

    # Disabled due to MN-1536
    disable_and_assert_traffic_fails(sender, action_loadbalancer)
    enable_and_assert_traffic_succeeds(sender, action_loadbalancer)


@nottest
@attr(version="v1.3.0", slow=False)
@bindings(binding_onehost, binding_multihost)
@with_setup(start_servers, stop_servers)
def test_long_connection_loadbalancing():
    """
    Title: Balances traffic correctly when topology changes during a long running connection.

    Scenario:
    When: A VM sends TCP packets to a VIP's IP address / port, long running connections.
    And:  We have 3 backends of equal weight.
    Then: When pool member disabled during connection, non-sticky connections should still succeed
          When other devices are disabled during connection, non-sticky connections should break
    """
    sender = BM.get_iface_for_port('bridge-000-003', 1)
    pool_member_1 = VTM.find_pool_member(backend_ip_port(1))
    pool_member_2 = VTM.find_pool_member(backend_ip_port(2))
    pool_member_3 = VTM.find_pool_member(backend_ip_port(3))
    # Disable all but one backend
    pool_member_2.disable()
    pool_member_3.disable()


    # Should point to the only enabled backend 10.0.2.1
    result = make_request_to(sender, STICKY_VIP, timeout=20, src_port=12345)
    assert_that(result, equal_to('10.0.2.1'))
    result = make_request_to(sender, NON_STICKY_VIP, timeout=20, src_port=12345)
    assert_that(result, equal_to('10.0.2.1'))

    # Disable the one remaining backend (STICKY) and enable another one (NON_STICKY)
    pool_member_1.disable()
    pool_member_2.enable()
    pool_member_2.enable()

    # Connections from the same src ip / port will be counted as the same ongoing connection
    # Sticky traffic fails - connection dropped. It should reroute to an enabled backend?
    result = make_request_to(sender, STICKY_VIP, timeout=20, src_port=12345)
    # Is that right? Shouldn't midonet change to another backend?
    assert_that(result, equal_to(''))
    #assert_request_fails_to(sender, STICKY_VIP, timeout=20, src_port=12345)
    # Non sticky traffic succeeds - connection allowed to continue
    result = make_request_to(sender, NON_STICKY_VIP, timeout=20, src_port=12345)
    # It's not the disabled backend
    assert_that(result, is_not(equal_to('10.0.2.1')))
    # But some backend answers
    assert_that(result, is_not(equal_to('')))

    # Re-enable the sticky backend
    pool_member_1.enable()

    assert_request_succeeds_to(sender, STICKY_VIP, timeout=20, src_port=12345)
    assert_request_succeeds_to(sender, NON_STICKY_VIP, timeout=20, src_port=12345)

    # When disabling the loadbalancer, both sticky and non sticky fail
    action_loadbalancer("disable")

    assert_request_fails_to(sender, STICKY_VIP)
    assert_request_fails_to(sender, NON_STICKY_VIP)

    action_loadbalancer("enable")

