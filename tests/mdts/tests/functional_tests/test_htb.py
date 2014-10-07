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
from mdts.tests.utils.asserts import *
from mdts.tests.utils.load import *
from mdts.tests.utils import *
from hamcrest import greater_than_or_equal_to, equal_to, assert_that
from nose.tools import with_setup, nottest
from nose.plugins.attrib import attr

import logging
import time

LOG = logging.getLogger(__name__)

PTM = PhysicalTopologyManager('../topologies/mmm_physical_test_htb.yaml')
VTM = VirtualTopologyManager('../topologies/mmm_virtual_test_htb.yaml')
BM = BindingManager(PTM, VTM)

RATE = 5000

bindings1 = {
    'description': 'on single MM',
    'bindings': [
        {'binding':
             {'device_name': 'bridge-000-001', 'port_id': 1,
              'host_id': 1, 'interface_id': 1}},
        {'binding':
             {'device_name': 'bridge-000-001', 'port_id': 2,
              'host_id': 1, 'interface_id': 2}},
        {'binding':
             {'device_name': 'bridge-000-002', 'port_id': 1,
              'host_id': 1, 'interface_id': 3}},
        {'binding':
             {'device_name': 'bridge-000-002', 'port_id': 2,
              'host_id': 1, 'interface_id': 4}}
        ]
    }

bindings2 = {
    'description': 'spanning across two MM, port1 and port2',
    'bindings': [
        {'binding':
             {'device_name': 'bridge-000-001', 'port_id': 1,
              'host_id': 1, 'interface_id': 1}},
        {'binding':
             {'device_name': 'bridge-000-001', 'port_id': 2,
              'host_id': 2, 'interface_id': 1}},
        {'binding':
             {'device_name': 'bridge-000-001', 'port_id': 3,
              'host_id': 1, 'interface_id': 3}},
        {'binding':
             {'device_name': 'bridge-000-001', 'port_id': 4,
              'host_id': 1, 'interface_id': 4}},
        {'binding':
             {'device_name': 'bridge-000-001', 'port_id': 5,
              'host_id': 1, 'interface_id': 5}},
        {'binding':
             {'device_name': 'bridge-000-001', 'port_id': 6,
              'host_id': 1, 'interface_id': 6}}
        ]
    }

bindings3 = {
    'description': 'spanning across two MM, port3 and port4',
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
              'host_id': 2, 'interface_id': 2}},
        {'binding':
             {'device_name': 'bridge-000-001', 'port_id': 5,
              'host_id': 1, 'interface_id': 5}},
        {'binding':
             {'device_name': 'bridge-000-001', 'port_id': 6,
              'host_id': 1, 'interface_id': 6}}
        ]
    }

def setup():
    PTM.build()
    VTM.build()

def teardown():
    time.sleep(2)
    PTM.destroy()
    VTM.destroy()

def verify_isolation(flood_source, flood_target, normal_source, normal_target):
    flood = run_loadgen(flood_source, flood_target, 0, 0, 5)
    try:
        packets = count_packets(normal_target, 1000,
                                'ether dst {0}'.format(normal_target.get_mac_addr()))
        normal = run_loadgen(normal_source, normal_target, 1, 25000, 200000)
        LOG.warn(normal.communicate())
        assert_that(captured_packets(packets), equal_to(25000))
    finally:
        flood.kill()

def feed_mac(src_itf, dst_itf):
    try:
        src_itf.send_arp_request(dst_itf.get_ip())
        time.sleep(1)
    except:
        LOG.warn('Sending ARP from the receiver VM failed.')
        raise

def warmup(src_itf, dst_itf):
    run_nmap(RATE * 2, src_itf, dst_itf).wait()

@attr(version="v1.4", slow=True)
@bindings(bindings1)
def test_single_rogue():
    """
    Title: Single Malicious VM

    Scenario:
        Given: Two bridges with 2 ports each
        When: A network flood is crossing bridge 1
        Then: An UDP scan crossing bridge 2 is completed in a bounded time

    Note: bindings1: no tunnel
          bindings2: port2 -> port1 over tunnel
          bindings3: port4 -> port3 over tunnel
    """
    rogue_source = BM.get_iface_for_port('bridge-000-001', 1)
    rogue_target = BM.get_iface_for_port('bridge-000-001', 2)
    feed_mac(rogue_source, rogue_target)
    warmup(rogue_source, rogue_target)

    normal_source = BM.get_iface_for_port('bridge-000-002', 1)
    normal_target = BM.get_iface_for_port('bridge-000-002', 2)

    verify_isolation(rogue_source, rogue_target, normal_source, normal_target)


@nottest
@attr(version="v1.4", slow=True)
@bindings(bindings1, bindings2, bindings3)
def test_two_rogue():
    """
    Title: Two Malicious Hosts

    Scenario 1:
    When: Given a bridge with 6 ports (port1-port6)
    Then: A UDP is reachable
          from port2 to port1
          scanning ports
          from port4 to port3 and
          from port6 to port5

    Note: bindings1: no tunnel
          bindings2: port2 -> port1 over tunnel
          bindings3: port4 -> port3 over tunnel
    """
    # rogue1 may be over tunnel (e.g. bindings3), so delay is set
    # to a slightly larger value decreasing the total load of two
    # agents.
    rogue1 = BM.get_iface_for_port('bridge-000-001', 4)
    mc = rogue1._delegate._proxy._concrete
    scanner1 = ScanPortFailure(mc._get_nsname(),
                               mc._get_peer_ifname(),
                               ('172.16.1.4', '1-10000'),
                               ('172.16.1.3', '1-10000'),
                               '200')

    rogue2 = BM.get_iface_for_port('bridge-000-001', 6)
    mc = rogue2._delegate._proxy._concrete
    scanner2 = ScanPortFailure(mc._get_nsname(),
                               mc._get_peer_ifname(),
                               ('172.16.1.6', '1-10000'),
                               ('172.16.1.5', '1-10000'),
                               '200')

    scanner1.inject()
    try:
        scanner2.inject()
        try:
            time.sleep(30)

            check_honest_with_random_udp() # under port scanning
        finally:
            scanner2.eject()
    finally:
        scanner1.eject()
        time.sleep(10)
