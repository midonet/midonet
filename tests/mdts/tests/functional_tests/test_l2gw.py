from nose.plugins.attrib import attr
from mdts.lib.physical_topology_manager import PhysicalTopologyManager
from mdts.lib.virtual_topology_manager import VirtualTopologyManager
from mdts.lib.binding_manager import BindingManager
from mdts.tests.utils.asserts import *
from mdts.tests.utils import *

from hamcrest import *

import logging
import time
import pdb
import re
import subprocess

FORMAT = '%(asctime)-15s %(module)s#%(funcName)s(%(lineno)d) %(message)s'
logging.basicConfig(format=FORMAT, level=logging.DEBUG)
LOG = logging.getLogger(__name__)

PTM = PhysicalTopologyManager('../topologies/mmm_physical_l2gw.yaml')
VTM = VirtualTopologyManager('../topologies/mmm_virtual_l2gw.yaml')
BM = BindingManager(PTM, VTM)

bindings1 = {
    'description': 'VM connected to MM with forwarding trunk',
    'bindings': [
        {'binding':{'device_name': 'bridge-000-001-0001', 'port_id': 1,
                    'host_id': 1, 'interface_id': 1}},
        {'binding':{'device_name': 'bridge-000-001', 'port_id': 3,
                    'host_id': 1, 'interface_id': 2}},
        {'binding':{'device_name': 'bridge-000-001', 'port_id': 4,
                    'host_id': 2, 'interface_id': 2}}]}

bindings2 = {
    'description': 'VM connected to MM with blocking trunk',
    'bindings': [
        {'binding':{'device_name': 'bridge-000-001-0001', 'port_id': 1,
                    'host_id': 2, 'interface_id': 1}},
        {'binding':{'device_name': 'bridge-000-001', 'port_id': 3,
                    'host_id': 1, 'interface_id': 2}},
        {'binding':{'device_name': 'bridge-000-001', 'port_id': 4,
                    'host_id': 2, 'interface_id': 2}}]}

bindings3 = {
    'description': 'VM connected to MM without trunk',
    'bindings': [
        {'binding':{'device_name': 'bridge-000-001-0001', 'port_id': 1,
                    'host_id': 3, 'interface_id': 1}},
        {'binding':{'device_name': 'bridge-000-001', 'port_id': 3,
                    'host_id': 1, 'interface_id': 2}},
        {'binding':{'device_name': 'bridge-000-001', 'port_id': 4,
                    'host_id': 2, 'interface_id': 2}}]}


def setup():
    PTM.build()
    VTM.build()
    # wait for stp to warm up and start forwarding
    time.sleep(30)


def teardown():
    time.sleep(5)
    PTM.destroy()
    VTM.destroy()


def _ping_from_mn(iface_in_mn, outside, count=40):

    f1 = iface_in_mn.ping4(outside, count=count)
    assert_that(outside, receives('dst host 172.16.0.224 and icmp',
                                  within_sec(20)))


def _ping_to_mn(iface_in_mn, outside):

    outside.clear_arp(sync=True)
    f1 = outside.ping4(iface_in_mn)
    assert_that(iface_in_mn, receives('dst host 172.16.0.1 and icmp',
                                      within_sec(5)))
    wait_on_futures([f1])

@bindings(bindings1)
def test_resiliency_from_transient_loop():
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
    iface1 = BM.get_iface_for_port('bridge-000-001-0001', 1)
    iface2 = BM.get_iface(4,1)

    try:
        _ping_from_mn(iface1, iface2, count=5)
    except AssertionError:
        LOG.debug("MN didn't recover from a bad state caused by a "
                 "transient loop within the timeframe of pinging 5 times.")

    # Give MM a chance for 120 sec to recover.
    # NOTE: MM needs to recover quicker, Product to come up with a requirement.
    time.sleep(120)

    # Now assert that ping works
    _ping_from_mn(iface1, iface2, count=5)


@attr(version="v1.2.0", slow=True)
@bindings(bindings1, bindings2, bindings3)
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

    # Wait for the peer bridge to block one of the trunks.
    # Othewise, the peer bridge would make the both ports forwarding
    # for a couple of seconds, which causes a loop.
    time.sleep(5)

    iface1 = BM.get_iface_for_port('bridge-000-001-0001', 1)
    iface2 = BM.get_iface(4,1)

    _ping_from_mn(iface1, iface2)


@attr(version="v1.2.0", slow=True)
@bindings(bindings1, bindings2, bindings3)
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

    iface1 = BM.get_iface_for_port('bridge-000-001-0001', 1)
    iface2 = BM.get_iface(4,1)
    _ping_to_mn(iface1, iface2)

# approx. 45 sec for the peer Linux bridge to failover:
# 15s to detect, 15s in listening, another 15s in learning
FAILOVER_WAIT_SEC = 45 + 5

# approx. 30 sec for the peer Linux bridge to failback:
# 15s in listening, 15s in learning
FAILBACK_WAIT_SEC = 30 + 5


# FIXME: https://midobugs.atlassian.net/browse/QA-158
@attr(version="v1.2.0", slow=True, flaky=True)
@bindings(bindings1, bindings2, bindings3)
def test_failback_icmp_from_mn():
    """
    Title: Failover/Failback with ICMP from MidoNet VLAN

    Scenario 1:
    Given: a VM is inside a MidoNet vlan
    When: a VM sends ICMP echo request with ping command
          to a peer on a VLAN that is outside MidoNet
    Then: the receiver VM should receive the ICMP echo packet.
    And: the ping command succeeds

    Scenario 2:
    When: the trunk interface loses the link
    Then: the VM still should have the connectivity described in the
          scenario 1 above.

    Scenario 3:
    When: the trunk interface gets the link back
    Then: the VM still should have the connectivity described in the
          scenario 1 above.

    """

    # wait for the peer bridge to warm up. see the comment in the first test
    time.sleep(5)

    iface1 = BM.get_iface_for_port('bridge-000-001-0001', 1)
    iface2 = BM.get_iface(4,1)

    _ping_from_mn(iface1, iface2)

    trunk0 = BM.get_iface_for_port('bridge-000-001', 3)

    trunk0.execute('ip link set dev $if down',sync=True)

    # wait for stp to failover
    time.sleep(FAILOVER_WAIT_SEC)

    _ping_from_mn(iface1, iface2)

    trunk0.execute('ip link set dev $if up',sync=True)

    # wait for stp to failback
    time.sleep(FAILBACK_WAIT_SEC)

    _ping_from_mn(iface1, iface2)


# FIXME: https://midobugs.atlassian.net/browse/QA-158
@attr(version="v1.2.0", slow=True, flaky=True)
@bindings(bindings1, bindings2, bindings3)
def test_failback_icmp_to_mn():
    """
    Title: Failover/Failback with ICMP to MidoNet VLAN

    Scenario 1:
    Given: a VM is inside a MidoNet vlan
    When: a host outside MidoNet that is on the same VLAN sends ICMP echo
          request with ping command to a VM on the MidoNet VLAN
    Then: the VM inside MidoNet VLAN should receive the ICMP echo packet.
    And: the ping command on the host succeeds


    Scenario 2:
    When: the trunk interface loses the link
    Then: the VM still should have the connectivity described in the
          scenario 1 above.


    Scenario 3:
    When: the trunk interface gets the link back
    Then: the VM still should have the connectivity described in the
          scenario 1 above.
    """

    # wait for the peer bridge to warm up. see the comment in the first test
    time.sleep(5)

    iface1 = BM.get_iface_for_port('bridge-000-001-0001', 1)
    iface2 = BM.get_iface(4,1)

    _ping_to_mn(iface1, iface2)

    trunk0 = BM.get_iface_for_port('bridge-000-001', 3)

    trunk0.execute('ip link set dev $if down',sync=True)

    # wait for stp to failover
    time.sleep(FAILOVER_WAIT_SEC)

    _ping_to_mn(iface1, iface2)

    trunk0.execute('ip link set dev $if up',sync=True)

    # wait for stp to failback
    time.sleep(FAILBACK_WAIT_SEC)

    _ping_to_mn(iface1, iface2)
