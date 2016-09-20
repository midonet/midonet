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

binding_multisession = {
    'description': 'two sessions in uplink #1 and one vm not in uplink',
    'bindings': [
        {'binding':
             {'device_name': 'bridge-000-001', 'port_id': 2,
              'host_id': 2, 'interface_id': 1}},
        {'binding':
             {'device_name': 'router-000-001', 'port_id': 2,
              'host_id': 1, 'interface_id': 2}}
    ]
}



# 1.1.1.1 is assigned to lo in ns000 emulating a public IP address
def ping_from_inet(count=5, interval=1, port=2, retries=3, ipv6="2001::1"):
    quagga = service.get_container_by_hostname('quagga1')
    quagga.try_command_blocking( "ip a add 2001::2/64 dev bgp1")
    quagga.try_command_blocking( "ping6 2001::1 -c 4")

def start_vpp():
    mm1 = service.get_container_by_hostname("midolman1")
    # ensure clean env
    mm1.exec_command("stop vpp")
    mm1.try_command_blocking("start vpp")
    mm1.exec_command("ip l delete dev uplink-vpp")
    mm1.try_command_blocking("ip l add name uplink-vpp type veth peer name uplink-ovs")
    mm1.try_command_blocking("ip l set uplink-vpp up")
    mm1.try_command_blocking("ip l set uplink-ovs up")
    mm1.try_command_blocking("vppctl create host-interface name uplink-vpp hw-addr 01:02:03:04:05:06")
    mm2.try_command_blocking("vppctl set int state host-uplink-vpp up")
    mm1.try_command_blocking("vppctl set int ip address host-uplink-vpp 2001::1/64")
    mm1.try_command_blocking("vppctl trace add af-packet-input 4")
    mm1.try_command_blocking("mm-dpctl interface -a uplink-ovs midonet")
    mm1.try_command_blocking("mm-dpctl flows -d midonet -e 86dd -i bgp0 -o uplink-ovs")
    mm1.try_command_blocking("mm-dpctl flows -d midonet -e 86dd -o bgp0 -i uplink-ovs")
    #mm1.try_command_blocking("ping6 2001::1 -c 3")
def stop_vpp():
    mm1 = service.get_container_by_hostname("midolman1")
    mm1.exec_command("mm-dpctl flows --delete -d midonet -e 86dd -i bgp0 -o uplink-ovs")
    mm1.exec_command("mm-dpctl flows --delete -d midonet -e 86dd -o bgp0 -i uplink-ovs")
    mm1.exec_command("ip l delete dev uplink-vpp")
    quagga = service.get_container_by_hostname('quagga1')
    quagga.exec_command("ip a del 2001::2/64 dev bgp1")
    mm1.try_command_blocking("stop vpp")

@attr(version="v1.2.0")
@with_setup(start_vpp, stop_vpp)
def test_uplink_ipv6():
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
    #add_bgp([uplink1_session1], route_direct)
    #ping_to_inet() # BGP #1 is working

    #add_bgp([uplink2_session1], route_direct)
    #ping_to_inet() # BGP #1 and #2 working
    ping_from_inet()


