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
from mdts.lib import subprocess_compat
from mdts.lib.physical_topology_manager import PhysicalTopologyManager
from mdts.lib.virtual_topology_manager import VirtualTopologyManager
from mdts.lib.binding_manager import BindingManager
from mdts.tests.utils.asserts import *
from mdts.tests.utils.utils import wait_on_futures
from mdts.tests.utils.utils import bindings

from hamcrest import *
from nose.tools import nottest

import logging
import time
import pdb
import random
import re
import subprocess

FORMAT = '%(asctime)-15s %(module)s#%(funcName)s(%(lineno)d) %(message)s'
LOG = logging.getLogger(__name__)

PTM = PhysicalTopologyManager('../topologies/mmm_physical_l2gw.yaml')
VTM = VirtualTopologyManager('../topologies/mmm_virtual_l2gw.yaml')
BM = BindingManager(PTM, VTM)

# TODO: these tests only cover a subset of l2gw scenarios. We may also need:
# * ping between vms on the same vlan (midolman <-> midolman)
# * ping between external interfaces on the same clan going through midonet
#   - two external interfaces attached to different bridges.
#     each MM with one forwarding trunk to each bridge
#     ping from external1 to external2 on the same vlan going through midonet

binding1 = {
    'description': 'VM connected to MM without trunk',
    'bindings': [
        {'binding': {'device_name': 'bridge-000-001-0001', 'port_id': 1,
                     'host_id': 3, 'interface_id': 1}},
        {'binding': {'device_name': 'bridge-000-001', 'port_id': 3,
                     'host_id': 1, 'interface_id': 2}},
        {'binding': {'device_name': 'bridge-000-001', 'port_id': 4,
                     'host_id': 2, 'interface_id': 2}}]}


def setup_wait():
    time.sleep(30)


def _ping_from_mn(midoVmIface, exHostIface, count=3, do_arp=False):

    f1 = async_assert_that(exHostIface,
                           receives('dst host 172.16.0.224 and icmp',
                                    within_sec(20)))
    f2 = midoVmIface.ping4(exHostIface, count=count, do_arp=do_arp)
    wait_on_futures([f1, f2])


def _ping_to_mn(midoVmIface, exHostIface, count=3, do_arp=False):

    exHostIface.clear_arp()
    f1 = async_assert_that(midoVmIface,
                           receives('dst host 172.16.0.1 and icmp',
                                    within_sec(5)))
    f2 = exHostIface.ping4(midoVmIface, count=count, do_arp=do_arp)
    wait_on_futures([f1, f2])


def _send_random_udp_to_mn(midoVmIface, exHostIface, count=3):

    exHostIface.send_udp(midoVmIface.get_mac_addr(), '172.16.0.1', 28,
                         src_port=random.randint(61000, 65000),
                         dst_port=random.randint(61000, 65000),
                         count=count)


def _test_resiliency_from_transient_loop(ping, midoVmIface, exHostIface):
    """
    Title: Resiliency from transient loop

    Scenario:
    Given: a VM is inside a MidoNet vlan
    When: the peer STP enabled bridge port connected to two trunks are
          both Forwarding, meaning there's a loop
    Then: the VM eventually should be able to ping  to a peer on a VLAN
          that is outside MidoNet

    Note: this is a regression test for MN-853.
    """
    try:
        ping(midoVmIface, exHostIface, count=5)
    except (AssertionError, subprocess.CalledProcessError):
        LOG.debug("MN didn't recover from a bad state caused by a "
                  "transient loop within the timeframe of pinging 5 times.")

        # Give MM a chance for 161 secs to recover
        # in which all the associated states are going to expire.
        # *1 hard timeout expiration
        #    (default 120 secs)
        # *2 mac-port learning expiration
        #    (bridge.mac_port_mapping_expire_millis; 30 secs by default)
        # *3 FlowController periodically checks for flow expiration
        #    (default 10 seconds)
        time.sleep(100)

        # Now assert that ping works
        #
        # See also MN-1346; Do ARP to force to refresh MAC learning in
        # Linux Bridge. If a wrong pair of MAC and port is learnt in
        # transient loop, that won't expire in 300 secs by default.
        #
        ping(midoVmIface, exHostIface, count=5, do_arp=True)


@attr(version="v1.2.0")
@bindings(binding1)
def test_icmp_from_mn():
    """
    Title: ICMP reachability from MidoNet VLAN

    Scenario 1:
    Given: a VM is inside a MidoNet vlan
    When: a VM sends ICMP echo request with ping command
          to a peer on a VLAN that is outside MidoNet
    Then: the receiver VM should receive the ICMP echo packet.
    And: the ping command succeeds
    """
    setup_wait()
    midoVmIface = BM.get_iface_for_port('bridge-000-001-0001', 1)
    exHostIface = BM.get_iface(4, 1)

    # Wait for the peer bridge to block one of the trunks and
    # make sure Midonet has recovered from a transient loop.
    _test_resiliency_from_transient_loop(_ping_from_mn, midoVmIface, exHostIface)

@attr(version="v1.2.0")
@bindings(binding1)
def test_icmp_to_mn():
    """
    Title: ICMP reachability to MidoNet VLAN

    Scenario 1:
    Given: a VM is inside a MidoNet vlan
    When: a VM outside MidoNet that is on the same VLAN sends ICMP echo
          request with ping command to a VM on the MidoNet VLAN
    Then: the VM inside MidoNet VLAN should receive the ICMP echo packet.
    And: the ping command succeeds
    """
    setup_wait()
    midoVmIface = BM.get_iface_for_port('bridge-000-001-0001', 1)
    exHostIface = BM.get_iface(4, 1)

    # Wait for the peer bridge to block one of the trunks and
    # make sure Midonet has recovered from a transient loop.
    _test_resiliency_from_transient_loop(_ping_to_mn, midoVmIface, exHostIface)

# approx. 50 sec for the peer Linux bridge to failover:
# 20s to detect, 15s in listening, another 15s in learning
# but actually we need to wait 2 minutes for the flow to expire
FAILOVER_WAIT_SEC = 50 + 5

# approx. 30 sec for the peer Linux bridge to failback:
# 15s in listening, 15s in learning
# but actually we need to wait 2 minutes for the flow to expire
FAILBACK_WAIT_SEC = 30 + 5

@nottest
def _test_failover(ping, failover, restore):

    midoVmIface = BM.get_iface_for_port('bridge-000-001-0001', 1)
    exHostIface = BM.get_iface(4, 1)

    # Wait for the peer bridge to block one of the trunks and
    # make sure Midonet has recovered from a transient loop.
    _test_resiliency_from_transient_loop(ping, midoVmIface, exHostIface)

    trunk0 = BM.get_iface_for_port('bridge-000-001', 3)

    failover(trunk0)
    try:
        # wait for stp to failover
        time.sleep(FAILOVER_WAIT_SEC)

        ping(midoVmIface, exHostIface, do_arp=True)
    finally:
        restore(trunk0)

# FIXME: coalesce duplicated methods
@nottest
def _test_failover_on_ifdown_with_icmp_from_mn():

    def failover(trunk):
        trunk.set_down()

    def restore(trunk):
        trunk.set_up()

    _test_failover(_ping_from_mn, failover, restore)

@attr(version="v1.2.0")
@bindings(binding1)
def test_failover_on_ifdown_with_icmp_from_mn():
    """
    Title: Failover on Network Interface Down
           with ARP/ICMP to MidoNet VLAN

    Scenario 1:
    When: MM detects a forwarding trunk interface is down immediately
    Then: MM invalidates flows

    """
    setup_wait()
    _test_failover_on_ifdown_with_icmp_from_mn()

@nottest
def _test_failover_on_ifdown_with_icmp_to_mn():

    def failover(trunk):
        trunk.set_down()

    def restore(trunk):
        trunk.set_up()

    _test_failover(_ping_to_mn, failover, restore)

@attr(version="v1.2.0")
@bindings(binding1)
def test_failover_on_ifdown_with_icmp_to_mn():
    """
    Title: Failover on Network Interface Down
           with ARP/ICMP to MidoNet VLAN

    Scenario 1:
    When: MM detects a forwarding trunk interface is down immediately
    Then: MM invalidates flows
    """
    setup_wait()
    _test_failover_on_ifdown_with_icmp_to_mn()

@nottest
def _test_failover_on_generic_failure_with_icmp_from_mn():

    def failover(trunk):
        trunk.inject_packet_loss(wait_time=5)

    def restore(trunk):
        trunk.eject_packet_loss(wait_time=5)

    _test_failover(_ping_from_mn, failover, restore)

@attr(version="v1.2.0")
@bindings(binding1)
def test_failover_on_generic_failure_with_icmp_from_mn():
    """
    Title: Failover on Generic Network Failure
           with ARP/ICMP from MidoNet VLAN

    Scenario 1:
    When: MM WON'T detect a failure in a forwarding trunk
    Then: MM migrates a MAC and invalidates the associated flows
    """
    setup_wait()
    _test_failover_on_generic_failure_with_icmp_from_mn()

@nottest
def _test_failover_on_generic_failure_with_icmp_to_mn():

    def failover(trunk):
        trunk.inject_packet_loss(wait_time=5)

    def restore(trunk):
        trunk.eject_packet_loss(wait_time=5)

    _test_failover(_ping_to_mn, failover, restore)

@attr(version="v1.2.0")
@bindings(binding1)
def test_failover_on_generic_failure_with_icmp_to_mn():
    """
    Title: Failover on Generic Network Failure
           with ARP/ICMP to MidoNet VLAN

    Scenario 1:
    When: MM will not detects a failure in a forwarding trunk
    Then: MM migrates a MAC and invalidates the associated flows
    """
    setup_wait()
    _test_failover_on_generic_failure_with_icmp_to_mn()

@nottest
def _test_failback(test_failover, ping, migrate=None):

    test_failover()

    # wait for stp to failback
    time.sleep(FAILBACK_WAIT_SEC)

    midoVmIface = BM.get_iface_for_port('bridge-000-001-0001', 1)
    exHostIface = BM.get_iface(4, 1)

    # Emit a random UDP expecting it to migrate the MAC back to trunk0
    if migrate:
        migrate(midoVmIface, exHostIface)
        # Give midolman some time to migrate MAC
        # See also MN-1819
        time.sleep(1)

    ping(midoVmIface, exHostIface)

@attr(version="v1.2.0")
@bindings(binding1)
def test_failback_on_ifdown_with_icmp_from_mn():
    """
    Title: Failover on Network Interface Down / Failback
           with ARP/ICMP from MidoNet VLAN
    """
    setup_wait()
    _test_failback(_test_failover_on_ifdown_with_icmp_from_mn,
                   _ping_from_mn, _send_random_udp_to_mn)

@attr(version="v1.2.0")
@bindings(binding1)
def test_failback_on_ifdown_with_icmp_to_mn():
    """
    Title: Failover on Network Interface Down / Failback
           with ARP/ICMP to MidoNet VLAN
    """
    setup_wait()
    _test_failback(_test_failover_on_ifdown_with_icmp_to_mn,
                   _ping_to_mn, _send_random_udp_to_mn)

@attr(version="v1.2.0")
@bindings(binding1)
def test_failback_on_generic_failure_with_icmp_from_mn():
    """
    Title: Failover on Generic Network Failure / Failback
           with ARP/ICMP from MidoNet VLAN
    """
    setup_wait()
    _test_failback(_test_failover_on_generic_failure_with_icmp_from_mn,
                   _ping_from_mn, _send_random_udp_to_mn)

@attr(version="v1.2.0")
@bindings(binding1)
def test_failback_on_generic_failure_with_icmp_to_mn():
    """
    Title: Failover on Generic Network Failure / Failback
           with ARP/ICMP to MidoNet VLAN
    """
    setup_wait()
    _test_failback(_test_failover_on_generic_failure_with_icmp_to_mn,
                   _ping_to_mn, _send_random_udp_to_mn)
