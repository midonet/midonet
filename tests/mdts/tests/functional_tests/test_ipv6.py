# Copyright 2016 Midokura SARL
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
from mdts.lib.virtual_topology_manager  import VirtualTopologyManager
from mdts.lib.binding_manager           import BindingManager
from mdts.services                      import service

from nose.plugins.attrib import attr
from mdts.tests.utils import *
from hamcrest import *
from nose.tools import nottest, with_setup

import time
import pdb
import re

PTM = PhysicalTopologyManager('../topologies/mmm_physical_test_bgp.yaml')
VTM = VirtualTopologyManager('../topologies/mmm_virtual_test_bgp.yaml')
# Cannot remove this variable even it is unused within the current file. Test running is relying on it somehow
BM = BindingManager(PTM, VTM) 

# Runs ping6 command with given ip6 address from given containter
# Count provides the number of packets ping will send. The number must be positive
def ping_from_inet(container, ipv6 = '2001::1', count=4):
    count = max(1, count)
    cmd = 'ping6 ' + ipv6 + ' -c ' + str(count)
    cont_services = service.get_container_by_hostname(container) 
    cont_services.try_command_blocking(cmd)

def start_vpp():
    mm1 = service.get_container_by_hostname('midolman1')
    # ensure clean env
    uplink_vpp_mac = '2e:0e:2f:68:ce:ea'
    mm1.exec_command        ('stop vpp')
    mm1.try_command_blocking('start vpp')
    mm1.exec_command        ('ip l delete dev uplink-vpp')
    mm1.try_command_blocking('ip l add name uplink-vpp address ' + uplink_vpp_mac + ' type veth peer name uplink-ovs')
    mm1.try_command_blocking('ip l set uplink-vpp up')
    mm1.try_command_blocking('ip l set uplink-ovs up')
    mm1.try_command_blocking('vppctl create host-interface name uplink-vpp hw-addr ' + uplink_vpp_mac)
    mm1.try_command_blocking('vppctl set int state host-uplink-vpp up')
    mm1.try_command_blocking('vppctl set int ip address host-uplink-vpp 2001::1/64')
    mm1.try_command_blocking('vppctl trace add af-packet-input 4')
    mm1.try_command_blocking('mm-dpctl interface -a uplink-ovs midonet')
    mm1.try_command_blocking('mm-dpctl flows -d midonet -e 86dd -i bgp0 -o uplink-ovs')
    mm1.try_command_blocking('mm-dpctl flows -d midonet -e 86dd -o bgp0 -i uplink-ovs')

    quagga = service.get_container_by_hostname('quagga1')
    quagga.try_command_blocking('ip a add 2001::2/64 dev bgp1')

def stop_vpp():
    mm1 = service.get_container_by_hostname('midolman1')
    mm1.exec_command('mm-dpctl flows --delete -d midonet -e 86dd -i bgp0 -o uplink-ovs')
    mm1.exec_command('mm-dpctl flows --delete -d midonet -e 86dd -o bgp0 -i uplink-ovs')
    mm1.exec_command('ip l delete dev uplink-vpp')
    quagga = service.get_container_by_hostname('quagga1')
    quagga.exec_command('ip a del 2001::2/64 dev bgp1')
    mm1.try_command_blocking('stop vpp')

@attr(version="v1.2.0")
@with_setup(start_vpp, stop_vpp)
def test_uplink_ipv6():
    """
    Title: ping ipv6 uplink of midolman1 from quagga1. VPP must respond

    """
    ping_from_inet('quagga1', '2001::1', 4)


