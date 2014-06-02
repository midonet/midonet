from mdts.lib.physical_topology_manager import PhysicalTopologyManager
from mdts.lib.virtual_topology_manager import VirtualTopologyManager
from mdts.lib.binding_manager import BindingManager
from mdts.lib.failure.scan_port_failure import ScanPortFailure
from mdts.tests.utils.asserts import *
from mdts.tests.utils import *
from nose.tools import with_setup, nottest
from nose.plugins.attrib import attr

import logging
import time
import os
import pdb
import random
import re
import subprocess

LOG = logging.getLogger(__name__)

PTM = PhysicalTopologyManager('../topologies/mmm_physical_test_htb.yaml')
VTM = VirtualTopologyManager('../topologies/mmm_virtual_test_htb.yaml')
BM = BindingManager(PTM, VTM)

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

def check_honest_with_random_udp():
    sender = BM.get_iface_for_port('bridge-000-001', 2)
    receiver = BM.get_iface_for_port('bridge-000-001', 1)

    sender.send_arp_request('172.16.1.1', sync=True)
    f1 = sender.send_udp(receiver.get_mac_addr(), '172.16.1.1', 28,
                         src_port=random.randint(61000, 65000),
                         dst_port=random.randint(61000, 65000),
                         delay=1, count=5)
    assert_that(receiver, receives('dst host 172.16.1.1 and udp',
                                   within_sec(5)))
    wait_on_futures([f1])

@nottest
@attr(version="v1.4", slow=True)
@bindings(bindings1, bindings2, bindings3)
def test_single_rogue():
    """
    Title: Single Malicious Host

    Scenario 1:
    When: Given a bridge with 4 ports (port1-port4)
    Then: A UDP is reachable
          from port2 to port1
          scanning ports
          from port4 to port3

    Note: bindings1: no tunnel
          bindings2: port2 -> port1 over tunnel
          bindings3: port4 -> port3 over tunnel
    """
    rogue1 = BM.get_iface_for_port('bridge-000-001', 4)
    mc = rogue1._delegate._proxy._concrete
    scanner1 = ScanPortFailure(mc._get_nsname(),
                               mc._get_peer_ifname(),
                               ('172.16.1.4', '1-10000'),
                               ('172.16.1.3', '1-10000'),
                               '100')

    scanner1.inject()
    try:
        time.sleep(30)

        check_honest_with_random_udp() # under port scanning
    finally:
        scanner1.eject()
        time.sleep(10)

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
