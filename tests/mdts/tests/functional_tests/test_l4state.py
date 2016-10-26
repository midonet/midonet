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
from nose.plugins.attrib import attr

from mdts.lib.binding_manager import BindingManager
from mdts.lib.physical_topology_manager import PhysicalTopologyManager
from mdts.lib.virtual_topology_manager import VirtualTopologyManager
from mdts.services import service
from mdts.tests.utils.asserts import *
from mdts.tests.utils.utils import await_port_active
from mdts.tests.utils.utils import bindings
from mdts.tests.utils.utils import wait_on_futures

import logging
import random
import time


LOG = logging.getLogger(__name__)
PTM = PhysicalTopologyManager('../topologies/mmm_physical_test_l4state.yaml')
VTM = VirtualTopologyManager('../topologies/mmm_virtual_test_l4state.yaml')
BM = BindingManager(PTM, VTM)


binding_l4state = {
    'description': 'on 3 MMs',
    'bindings': [
        {'binding':
            {'device_name': 'router-000-001', 'port_id': 1,
             'host_id': 1, 'interface_id': 1}},
        {'binding':
            {'device_name': 'router-000-001', 'port_id': 2,
             'host_id': 2, 'interface_id': 1}},
        {'binding':
            {'device_name': 'router-000-001', 'port_id': 3,
             'host_id': 3, 'interface_id': 1}},
    ]
}


def get_random_port_num():
    '''Returns a random port number from a free port range.

    NOTE: Using a random number may cause test indeterminacy on a rare occasion.
    '''
    return random.randint(49152, 65535)


##############################################################################
#
# Scenario:
#
#          host-2                                 host-3
#            |                                      |
#            |           +---------------+          |
#            |           |               |          |
#            +-----------+    Router     +----------+
#  172.16.42.254/24 - 2  |               | 3 - 172.16.84.254/24
#                        +-------+-------+
#                                | 1 - 192.168.0.254/24
#                                |
#                                |
#                              host-1
#
#   * 2 & 3 form a port group.
#   * This chain will apply to all traffic:
#      * input-port == 2 | input-port == 3  ---> REV_DNAT && ACCEPT
#      * input-port == 1 && src = 192.168.0.1 && dst == 21.42.84.168
#           && is-forward flow ---> DNAT to 172.16.42.1:80 && ACCEPT
#      * ----> DROP
#
##############################################################################

def downlink_iface():
    return BM.get_iface_for_port('router-000-001', 1)


def downlink_port():
    return VTM.get_router('router-000-001').get_port(1)


def left_uplink_iface():
    return BM.get_iface_for_port('router-000-001', 2)


def left_uplink_port():
    return VTM.get_router('router-000-001').get_port(2)


def right_uplink_iface():
    return BM.get_iface_for_port('router-000-001', 3)


def right_uplink_port():
    return VTM.get_router('router-000-001').get_port(3)


def mac_for(port):
    return port.get_mn_resource().get_port_mac()


def feed_mac(port, iface):
    try:
        ip = port.get_mn_resource().get_port_address()
        iface.send_arp_request(ip)
        time.sleep(1)
    except:
        LOG.warn('Oops, sending ARP from the receiver VM failed.')
        raise


def feed_macs():
    feed_mac(downlink_port(), downlink_iface())
    feed_mac(left_uplink_port(), left_uplink_iface())
    feed_mac(right_uplink_port(), right_uplink_iface())


def setup_function():
    router = VTM.get_router('router-000-001')
    infilter = VTM.get_chain('router_infilter')
    router.set_inbound_filter(infilter)
    time.sleep(5)


def check_forward_flow(src_port_no):
    dst_mac = mac_for(downlink_port())
    fs = expect_forward()
    f = downlink_iface().send_udp(dst_mac,
                                  '21.42.84.168',
                                  41,
                                  src_port=src_port_no,
                                  dst_port=1080)
    wait_on_futures([f, fs])


def check_return_flow(port, iface, dst_port_no, dropped=False, retries=0):
    dst_mac = mac_for(port)
    if dropped:
        fs = expect_return_dropped(dst_port_no)
    else:
        fs = expect_return(dst_port_no)

    f = iface.send_udp(dst_mac,
                       '192.168.0.1',
                       41,
                       src_port=80,
                       dst_port=dst_port_no,
                       src_ipv4='172.16.42.1')
    try:
        wait_on_futures([f, fs])
    except:
        if retries > 0:
            time.sleep(5)
            check_return_flow(port, iface, dst_port_no, dropped, retries - 1)
        else:
            raise


def forward_filter():
    return 'dst host 172.16.42.1 and udp port 80'


def expect_forward():
    return async_assert_that(left_uplink_iface(),
                             receives(forward_filter(), within_sec(5)),
                             'Forward flow is DNATed and gets through.')


def return_filter(dst_port_no):
    filter_ = 'udp'
    filter_ += ' and dst host %s and dst port %d' % ('192.168.0.1', dst_port_no)
    filter_ += ' and src host %s and src port %d' % ('21.42.84.168', 1080)
    return filter_


def expect_return(dst_port_no):
    return async_assert_that(
        downlink_iface(),
        receives(return_filter(dst_port_no), within_sec(10)),
        'Return flow is rev-DNATed and gets through.')


def expect_return_dropped(dst_port_no):
    return async_assert_that(
        downlink_iface(),
        should_NOT_receive(return_filter(dst_port_no), within_sec(10)),
        'Return flow gets dropped.')

# Start/stop/wait for active ports only on the relevant agents to not introduce
# unnecessary delays. Agents already take their time to restart so if we wait
# too much we risk expiring keys on cassandra.


def stop_midolman_agents():
    agents = [service.get_container_by_hostname('midolman1'),
              service.get_container_by_hostname('midolman2')]
    for agent in agents:
        agent.stop()
    for agent in agents:
        agent.wait_for_status("down")


def start_midolman_agents():
    agents = [service.get_container_by_hostname('midolman1'),
              service.get_container_by_hostname('midolman2')]
    for agent in agents:
        agent.start()
    for agent in agents:
        agent.wait_for_status("up")


def reboot_agents(sleep_secs):
    stop_midolman_agents()
    await_ports(active=False)

    time.sleep(sleep_secs)

    start_midolman_agents()
    await_ports(active=True)


def await_ports(active):
    await_port_active(left_uplink_port()._mn_resource.get_id(), active=active)
    await_port_active(downlink_port()._mn_resource.get_id(), active=active)


@attr(version="v1.6.0")
@bindings(binding_l4state)
def test_distributed_l4():
    '''
    Title: Tests that stateful flows work across multiple agent instances

    Scenario:
    When: A VM establishes a conntrack'ed, DNAT'ed connection
    Then: Recipients can send back over two different routes
    '''
    setup_function()
    feed_macs()
    port_num = get_random_port_num()

    for i in range(0, 4):
        check_forward_flow(port_num)
        check_return_flow(left_uplink_port(), left_uplink_iface(), port_num)
        check_return_flow(right_uplink_port(), right_uplink_iface(), port_num)
        time.sleep(30)


@attr(version="v1.6.0")
@bindings(binding_l4state)
def test_distributed_l4_expiration():
    '''
    Title: Tests that stateful flows expire after inactivity

    Scenario:
    When: A VM establishes a conntrack'ed, DNAT'ed connection
    Then: Recipients can send back over two different routes
    Then: After 90 seconds of inactivity, the return flow breaks
    '''
    setup_function()
    feed_macs()

    port_num = get_random_port_num()

    check_forward_flow(port_num)
    check_return_flow(left_uplink_port(), left_uplink_iface(), port_num)

    time.sleep(90)  # Less than the 2 minute hard expiration for return flows

    check_return_flow(left_uplink_port(), left_uplink_iface(),
                      port_num, dropped=True)


@attr(version="v1.6.0")
@bindings(binding_l4state)
def test_distributed_l4_port_binding():
    '''
    Title: Tests that state is imported when a port is bound

    Scenario:
    When: A VM establishes a conntrack'ed, DNAT'ed connection
    Then: Recipients can send back over two different routes
    Then: Midolman agents are rebooted
    Then: The pre-existing nat mappings are not lost and connections are not
          broken
    '''
    setup_function()
    feed_macs()

    port_num = get_random_port_num()

    check_forward_flow(port_num)
    check_return_flow(left_uplink_port(), left_uplink_iface(), port_num)

    reboot_agents(0)

    check_return_flow(left_uplink_port(), left_uplink_iface(), port_num, retries=10)


@attr(version="v1.6.0")
@bindings(binding_l4state)
def test_distributed_l4_storage_ttl():
    '''
    Title: Tests that state has a correct TTL in storage

    Scenario:
    When: A VM establishes a conntrack'ed, DNAT'ed connection
    Then: Recipients can send back over two different routes
    Then: Midolman agents are stopped and restarted after 90 seconds
    Then: The pre-existing nat mappings are lost and connections are broken
    '''
    setup_function()
    feed_macs()

    port_num = get_random_port_num()

    check_forward_flow(port_num)
    check_return_flow(left_uplink_port(), left_uplink_iface(), port_num)

    reboot_agents(120)

    check_return_flow(left_uplink_port(), left_uplink_iface(),
                      port_num, dropped=True)
