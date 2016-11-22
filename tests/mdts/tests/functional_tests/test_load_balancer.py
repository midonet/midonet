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

from collections import Counter
from hamcrest.core import assert_that
from hamcrest import equal_to, is_not
from nose.plugins.attrib import attr
from nose.tools import with_setup, nottest

from mdts.lib.binding_manager import BindingManager
from mdts.lib.physical_topology_manager import PhysicalTopologyManager
from mdts.lib.virtual_topology_manager import VirtualTopologyManager
from mdts.services import service
from mdts.tests.utils.utils import bindings
from mdts.tests.utils.asserts import async_assert_that, receives, should_NOT_receive, within_sec
from mdts.tests.utils.utils import wait_on_futures

import logging
import time

LOG = logging.getLogger(__name__)
PTM = PhysicalTopologyManager('../topologies/mmm_physical_test_load_balancer.yaml')
VTM = VirtualTopologyManager('../topologies/mmm_virtual_test_load_balancer.yaml')
BM = BindingManager(PTM, VTM)


binding_multihost = {
    'description': 'spanning across multiple MMs (equal weight, sender on different subnet)',
    'bindings': [
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
    ],
    'vips': {
        'non_sticky_vip': '100.100.2.8',
        'sticky_vip': '100.100.2.9'
    },
    'sender': ('bridge-000-003', 1),
    'weighted': False
}

binding_multihost_same_subnet = {
    'description': 'spanning across multiple MMs (equal weight, sender on same subnet)',
    'bindings': [
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
            {'device_name': 'bridge-000-002', 'port_id': 4,
             'host_id': 1, 'interface_id': 8}}
    ],
    'vips': {
        'non_sticky_vip': '100.100.2.8',
        'sticky_vip': '100.100.2.9'
    },
    'sender': ('bridge-000-002', 4),
    'weighted': False
}

binding_multihost_weighted = {
    'description': 'spanning across multiple MMs (different weights)',
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
            {'device_name': 'bridge-000-003', 'port_id': 1,
             'host_id': 1, 'interface_id': 7}}
    ],
    'vips': {
        'non_sticky_vip': '100.100.1.8',
        'sticky_vip': '100.100.1.9'
    },
    'sender': ('bridge-000-003', 1),
    'weighted': True
}

DST_PORT = 10000

NUM_BACKENDS = 3

SRC_PORT = 40000

SERVERS = dict()


################################ Helper methods

def disable_and_assert_traffic_fails(sender, action_fun, **kwargs):
    # Do action
    action_fun("disable", **kwargs)

    time.sleep(5)

    # Make one request to the non sticky loadbalancer IP, should fail
    assert_request_fails_to(sender, kwargs['vips']['non_sticky_vip'])

    # Make one request to the sticky loadbalancer IP, should fail
    assert_request_fails_to(sender, kwargs['vips']['sticky_vip'])


def enable_and_assert_traffic_succeeds(sender, action_fun, **kwargs):
    # Do action
    action_fun("enable", **kwargs)

    # Check that we have an haproxy running for each pool.
    lb_pools = VTM.get_load_balancer('lb-000-001').get_pools()
    get_current_leader(lb_pools)

    # Make one request to the non sticky loadbalancer IP, should succeed
    assert_request_succeeds_to(sender, kwargs['vips']['non_sticky_vip'])

    # Make one request to the sticky loadbalancer IP, should succeed
    assert_request_succeeds_to(sender, kwargs['vips']['sticky_vip'])


def backend_ip_port(num):
    backend_if = get_backend_if(num)
    backend_ip = backend_if.get_ip()
    return backend_ip, DST_PORT


def get_backend_if(num):
    # Get the bridge of the first binding <-> first backend
    backend_bridge = BM.get_binding_data()['bindings'][0]['binding']['device_name']
    return BM.get_iface_for_port(backend_bridge, num)


def action_loadbalancer(fun_name, **kwargs):
    first_pool_member = VTM.find_pool_member(backend_ip_port(1))
    lb = first_pool_member._pool._load_balancer
    getattr(lb, fun_name)()


def action_vips(fun_name, **kwargs):
    for current_vip in kwargs['vips'].values():
        vip = VTM.find_vip((current_vip, DST_PORT))
        getattr(vip, fun_name)()


def action_pool(fun_name, **kwargs):
    first_pool_member = VTM.find_pool_member(backend_ip_port(1))
    pool = first_pool_member._pool
    getattr(pool, fun_name)()


def action_pool_members(fun_name, **kwargs):
    for backend_num in range(1, NUM_BACKENDS + 1):
        pool_member = VTM.find_pool_member(backend_ip_port(backend_num))
        getattr(pool_member, fun_name)()


def await_member_status(backend_num, status, timeout=30, sleep_time=2):
    pm_id = VTM.find_pool_member(backend_ip_port(backend_num)).\
        get_mn_resource().\
        get_id()
    elapsed = 0
    while elapsed < timeout:
        if VTM._api.get_pool_member(pm_id).get_status() == status:
            LOG.debug("L4LB: pool member %s became %s." % (pm_id, status))
            return
        LOG.debug("L4LB: pool member %s not %s yet." % (pm_id, status))
        elapsed += sleep_time
        time.sleep(sleep_time)
    raise RuntimeError("L4LB: Pool member did not become %s after %d s." % (
        status,
        timeout
    ))


def start_server(backend_num):
    global SERVERS

    backend_ip, backend_port = backend_ip_port(backend_num)
    backend_if = get_backend_if(backend_num)
    f = backend_if.execute("ncat -l %s %s -k -e '/bin/echo %s'" % (
        backend_ip, backend_port, backend_ip
    ))
    output_stream, exec_id = f.result()
    backend_if.compute_host.ensure_command_running(exec_id)
    SERVERS.setdefault(backend_num, backend_if)


def stop_server(backend_num):
    global SERVERS
    backend_if = SERVERS[backend_num]
    pid = backend_if.execute(
        'sh -c "netstat -ntlp | grep ncat | awk \'{print $7}\' | cut -d/ -f1"',
        sync=True)
    backend_if.execute("kill -9 %s" % pid)
    LOG.debug("L4LB: killing backend hosted in (%s, %s)." % (
        backend_if.compute_host.get_hostname(),
        backend_if.get_ifname()
    ))
    del SERVERS[backend_num]


def start_servers():
    for backend_num in range(1, NUM_BACKENDS + 1):
        start_server(backend_num)
    set_filters('router-000-001', 'rev_snat', 'snat')
    lb_pools = VTM.get_load_balancer('lb-000-001').get_pools()
    get_current_leader(lb_pools)


def stop_servers():
    global SERVERS
    for backend_num in range(1, NUM_BACKENDS + 1):
        stop_server(backend_num)
    SERVERS = dict()
    unset_filters('router-000-001')


def make_request_to(sender, dest, timeout=10, src_port=None):
    global SRC_PORT

    if src_port is None:
        SRC_PORT += 1
    port = src_port if src_port is not None else SRC_PORT
    cmd_line = 'ncat --recv-only -w %d -p %d %s %d' % (
        timeout,
        port,
        dest,
        DST_PORT
    )
    result = sender.execute(cmd_line, timeout, sync=True)
    LOG.debug("L4LB: request to %s. Response: %s" % (sender, result))
    return result


def make_n_requests_to(sender, num_reqs, dest, timeout=30, src_port=None):
    # Executing a command on docker takes a non-trivial amount of time. Instead
    # of executing n command, execute only one with a loop to save that setup
    # command. This change allows us to increase the amount of requests in the
    # tests without increasing its duration.
    global SRC_PORT
    port = src_port if src_port is not None else '$((%d+i))' % SRC_PORT
    cmd = 'sh -c \"for i in `seq 1 %d`; ' \
          'do ncat --recv-only -w %d -p %s %s %d; ' \
          'done\"' % (num_reqs, 5, port, dest, DST_PORT)
    LOG.debug("L4LB: executing %s" % cmd)
    result = sender.execute(cmd, timeout, sync=True)
    if src_port is None:
        SRC_PORT += num_reqs  # to account for the loop in the cmd above
    return result.split('\n')


def assert_request_succeeds_to(sender, dest, timeout=10):
    result = make_request_to(sender, dest, timeout)
    assert_that(result, is_not(equal_to('')))


def assert_request_fails_to(sender, dest, timeout=10):
    result = make_request_to(sender, dest, timeout)
    assert_that(result, equal_to(''))


# TODO: this function is replicated in several tests
# Move to the utils package in a refactor patch
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


def check_weighted_results(results):
    # check that the # of requests is higher according to the backend weight
    # list of tuples (ip, hits)
    ordered_results = Counter(results).most_common()
    weights = [(member_ip,
                VTM.find_pool_member((member_ip, DST_PORT)).get_weight())
               for member_ip, _ in ordered_results]
    # list of tuples (ip, weight)
    ordered_weights = sorted(weights, key=lambda x: x[1], reverse=True)
    LOG.debug("L4LB: checking weighted results -> %s weights -> %s" %
              (ordered_results, ordered_weights))
    return zip(*ordered_results)[0] == zip(*ordered_weights)[0]


def check_num_backends_hit(results, num_backends):
    LOG.debug("L4LB: checking %s contains %s backends",
              results,
              num_backends)
    return len(set(results)) == num_backends


def get_current_leader(lb_pools, timeout=60, wait_time=5):
    agents = service.get_all_containers('midolman')
    current_leader = None
    num_leaders = 0
    haproxies = []
    while timeout > 0:
        for agent in agents:
            # Check that we have an haproxy running for each pool to be
            # considered a full leader
            haproxies = []
            for lb_pool in lb_pools:
                if agent.hm_resources_exist(lb_pool.get_id()):
                    haproxies.append(lb_pool.get_id())
                else:
                    break

            if len(haproxies) == len(lb_pools):
                current_leader = agent
                num_leaders += 1

        assert_that(num_leaders <= 1,
                    True,
                    'L4LB: More than one agent running haproxy instances')
        if num_leaders == 0:
            LOG.debug('L4LB: No haproxy leaders found! Retrying...')
            time.sleep(wait_time)
            timeout -= wait_time
        else:
            LOG.debug('L4LB: current leader is %s' % current_leader.get_hostname())
            return current_leader

    raise RuntimeError('Not all haproxy instances found! '
                       'Only pools %s have an haproxy instance.' % haproxies)


@attr(version="v1.3.0")
@bindings(binding_multihost,
          binding_multihost_weighted,
          binding_multihost_same_subnet)
@with_setup(start_servers, stop_servers)
def test_multi_member_loadbalancing():
    """
    Title: Balances traffic correctly when multiple pool members are active,
           behaves differently based on sticky source IP enabled / disabled.

    Scenario:
    When: A VM sends TCP packets to a VIP's IP address / port.
    And:  We have 3 backends of equal weight or different weight depending on
          the binding.
    Then: The loadbalancer sends some traffic to each backend when sticky
          source IP disabled, all to one backend if enabled.
    """

    await_member_status(1, status='ACTIVE')
    await_member_status(2, status='ACTIVE')
    await_member_status(3, status='ACTIVE')

    # The probability of hitting all 3 backends at least once with n requests
    # (considering equal weights) is formulated in the coupon collector's
    # problem (see Wikipedia for instance).
    # The expected number of requests for 3 backends is 6.
    # With 400 requests, we play it on the safe side for the case with different
    # backend weights.
    num_reqs = 400

    binding = BM.get_binding_data()
    vips = binding['vips']
    weighted = binding['weighted']
    sender_bridge, sender_port = binding['sender']

    LOG.debug("L4LB: sending from bridge %s at port %s" % (
        sender_bridge, sender_port))
    sender = BM.get_iface_for_port(sender_bridge, sender_port)

    # Make many requests to the non sticky loadbalancer IP, hits all 3 backends
    LOG.debug("L4LB: make requests to NON_STICKY_VIP")
    non_sticky_results = make_n_requests_to(sender,
                                            num_reqs,
                                            vips['non_sticky_vip'])
    LOG.debug("L4LB: non_sticky results %s" % non_sticky_results)
    assert_that(check_num_backends_hit(non_sticky_results, 3), True)
    if weighted:
        assert_that(check_weighted_results(non_sticky_results), True)

    # Make many requests to the sticky loadbalancer IP, hits exactly one backend
    LOG.debug("L4LB: make requests to STICKY_VIP")
    sticky_results = make_n_requests_to(sender,
                                        num_reqs,
                                        vips['sticky_vip'])
    LOG.debug("L4LB: sticky results %s" % sticky_results)
    assert_that(check_num_backends_hit(sticky_results, 1), True)

    # Disable (admin state down) the backend we are "stuck" to
    LOG.debug("L4LB: disable one backend: %s" % sticky_results[0])
    stuck_backend = sticky_results[0]
    stuck_pool_member = VTM.find_pool_member((stuck_backend, DST_PORT))
    stuck_pool_member.disable()

    # We only have 2 backends now, so we need fewer requests to hit the 2
    # backends at least once.
    num_reqs = 200

    # Make many requests to the non sticky loadbalancer IP, hits the 2 remaining backends
    LOG.debug("L4LB: make requests to NON_STICKY_VIP (one backend disabled)")
    non_sticky_results = make_n_requests_to(sender,
                                            num_reqs,
                                            vips['non_sticky_vip'])
    LOG.debug("L4LB: non_sticky results %s" % non_sticky_results)
    assert_that(check_num_backends_hit(non_sticky_results, 2), True)
    if weighted:
        assert_that(check_weighted_results(non_sticky_results), True)
    assert_that(stuck_backend not in non_sticky_results)

    # Make many requests to the sticky loadbalancer IP, hits exactly one backend
    LOG.debug("L4LB: make requests to STICKY_VIP (one backend disabled)")
    sticky_results = make_n_requests_to(sender,
                                        num_reqs,
                                        vips['sticky_vip'])
    LOG.debug("L4LB: sticky results %s" % sticky_results)
    assert_that(check_num_backends_hit(sticky_results, 1), True)
    assert_that(stuck_backend not in sticky_results)

    # Re-enable the pool member we disabled
    stuck_pool_member.enable()


@attr(version="v1.3.0")
@bindings(binding_multihost)
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
    vips = BM.get_binding_data()['vips']
    sender_bridge, sender_port = BM.get_binding_data()['sender']
    sender = BM.get_iface_for_port(sender_bridge, sender_port)

    # For each device in the L4LB topology:
    # - Disable the device, test hitting VIP fails
    # - Re-enable the device, test hitting VIP succeeds
    disable_and_assert_traffic_fails(sender, action_pool_members, vips=vips)
    enable_and_assert_traffic_succeeds(sender, action_pool_members, vips=vips)

    disable_and_assert_traffic_fails(sender, action_pool, vips=vips)
    enable_and_assert_traffic_succeeds(sender, action_pool, vips=vips)

    # Disabled due to MN-1536
    disable_and_assert_traffic_fails(sender, action_vips, vips=vips)
    enable_and_assert_traffic_succeeds(sender, action_vips, vips=vips)

    # Disabled due to MN-1536
    disable_and_assert_traffic_fails(sender, action_loadbalancer, vips=vips)
    enable_and_assert_traffic_succeeds(sender, action_loadbalancer, vips=vips)


@bindings(binding_multihost)
@with_setup(start_servers, stop_servers)
def test_haproxy_failback():
    """
    Title: HAProxy instance resilience test

    Scenario:
    When: A load balancer is configured with a pool of three backends
    And: A health monitor in a distributed setting (one agent acting as the
         haproxy leader)
    And: we induce failures on the leader
    Then: haproxy instance should have been moved to another alive agent,
    jumping until the first agent is used
          again
    :return:
    """

    def check_haproxy_down(agent, lb_pools, timeout=60, wait_time=5):
        while timeout > 0:
            is_running = False
            for lb_pool in lb_pools:
                if agent.hm_resources_exist(lb_pool.get_id()):
                    is_running = True

            if is_running:
                timeout -= wait_time
                time.sleep(wait_time)
            else:
                return

        raise RuntimeError("HAProxy instance and namespaces still "
                           "show up upon restart.")
    # Get all pool ids
    lb_pools = VTM.get_load_balancer('lb-000-001').get_pools()

    failbacks = 6
    leaders_elected = set()
    while failbacks > 0 and len(leaders_elected) < 3:
        # Induce failure on the haproxy leader
        leader = get_current_leader(lb_pools)
        LOG.debug("L4LB: leader is %s" % leader.get_hostname())
        leaders_elected.add(leader.get_hostname())
        # Restart the leader (and check that no haproxy is there) so we pick
        # another one haproxy leader
        leader.restart(wait=True)
        check_haproxy_down(leader, lb_pools)
        failbacks -= 1

    assert_that(len(leaders_elected) == 3,
                True,
                'L4LB: not all agents were elected as leaders %s' %
                leaders_elected)


@bindings(binding_multihost)
@with_setup(start_servers, stop_servers)
@nottest  # MI-185
def test_health_monitoring_backend_failback():
    """
    Title: Health monitoring backend failure resilience test

    Scenario:
    When: A load balancer is configured with a pool of three backends
    And: A health monitor in a distributed setting (one agent acting as the
         haproxy leader)
    And: we induce failures on the backends
    Then: haproxy instance detects the failed backend and requests to the VIP
          should only go to the alive backends
    :return:
    """
    vips = BM.get_binding_data()['vips']
    sender_bridge, sender_port = BM.get_binding_data()['sender']
    sender = BM.get_iface_for_port(sender_bridge, sender_port)

    # Check that the three backends are active before starting the test
    await_member_status(1, status='ACTIVE')
    await_member_status(2, status='ACTIVE')
    await_member_status(3, status='ACTIVE')

    non_sticky_results = make_n_requests_to(sender,
                                            100,
                                            vips['non_sticky_vip'])
    LOG.debug("L4LB: non_sticky results %s (all backends alive)" %
              non_sticky_results)
    # Check that the three backends are alive
    assert_that(check_num_backends_hit(non_sticky_results, 3), True)

    # Fail one backend
    stop_server(1)
    await_member_status(1, status='INACTIVE')

    non_sticky_results = make_n_requests_to(sender,
                                            50,
                                            vips['non_sticky_vip'])
    LOG.debug("L4LB: non_sticky results %s (one backend failed)" %
              non_sticky_results)
    # Check that two of the three backends are alive
    assert_that(check_num_backends_hit(non_sticky_results, 2), True)

    # Fail second backend
    stop_server(2)
    await_member_status(2, status='INACTIVE')

    non_sticky_results = make_n_requests_to(sender,
                                            10,
                                            vips['non_sticky_vip'])
    LOG.debug("L4LB: non_sticky results %s (two backends failed)" %
              non_sticky_results)
    # Check that one of the three backends is alive
    assert_that(check_num_backends_hit(non_sticky_results, 1), True)

    # Recover failed backends
    start_server(1)
    start_server(2)
    await_member_status(1, status='ACTIVE')
    await_member_status(2, status='ACTIVE')

    non_sticky_results = make_n_requests_to(sender,
                                            100,
                                            vips['non_sticky_vip'])
    LOG.debug("L4LB: non_sticky results %s (all backends alive again)" %
              non_sticky_results)
    # Check that the three backends are alive
    assert_that(check_num_backends_hit(non_sticky_results, 3), True)


@nottest
@attr(version="v1.3.0")
@bindings(binding_multihost)
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
    vips = BM.get_binding_data()['vips']
    sender_bridge, sender_port = BM.get_binding_data()['sender']
    sender = BM.get_iface_for_port(sender_bridge, sender_port)

    pool_member_1 = VTM.find_pool_member(backend_ip_port(1))
    pool_member_2 = VTM.find_pool_member(backend_ip_port(2))
    pool_member_3 = VTM.find_pool_member(backend_ip_port(3))

    # Disable all but one backend
    pool_member_2.disable()
    pool_member_3.disable()

    # Should point to the only enabled backend 10.0.2.1
    result = make_request_to(sender,
                             vips['sticky_vip'],
                             timeout=20)
    assert_that(result, equal_to('10.0.2.1'))
    result = make_request_to(sender,
                             vips['non_sticky_vips'],
                             timeout=20)
    assert_that(result, equal_to('10.0.2.1'))

    # Disable the one remaining backend (STICKY) and enable another one (NON_STICKY)
    pool_member_1.disable()
    pool_member_2.enable()
    pool_member_2.enable()

    # Connections from the same src ip / port will be counted as the same ongoing connection
    # Sticky traffic fails - connection dropped. It should reroute to an enabled backend?
    result = make_request_to(sender, vips['sticky_vip'], timeout=20)
    # Is that right? Shouldn't midonet change to another backend?
    assert_that(result, equal_to(''))
    #assert_request_fails_to(sender, STICKY_VIP, timeout=20, src_port=12345)
    # Non sticky traffic succeeds - connection allowed to continue
    result = make_request_to(sender, vips['non_sticky_vip'], timeout=20)
    # It's not the disabled backend
    assert_that(result, is_not(equal_to('10.0.2.1')))
    # But some backend answers
    assert_that(result, is_not(equal_to('')))

    # Re-enable the sticky backend
    pool_member_1.enable()

    assert_request_succeeds_to(sender, vips['sticky_vip'], timeout=20)
    assert_request_succeeds_to(sender, vips['non_sticky_vip'], timeout=20)

    # When disabling the loadbalancer, both sticky and non sticky fail
    action_loadbalancer("disable")

    assert_request_fails_to(sender, vips['sticky_vip'])
    assert_request_fails_to(sender, vips['non_sticky_vip'])

    action_loadbalancer("enable")
