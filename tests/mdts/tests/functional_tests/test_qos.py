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
from mdts.lib.failure.service_failure import ServiceFailure

from mdts.lib.physical_topology_manager import PhysicalTopologyManager
from mdts.lib.virtual_topology_manager import VirtualTopologyManager
from mdts.lib.binding_manager import BindingManager
from mdts.lib.failure.no_failure import NoFailure
from mdts.tests.utils.asserts import *
from nose.plugins.attrib import attr
from mdts.tests.utils.utils import bindings
from mdts.tests.utils.utils import failures
from mdts.tests.utils.utils import wait_on_futures

import logging
import time

LOG = logging.getLogger(__name__)

PTM = PhysicalTopologyManager('../topologies/mmm_physical_test_qos.yaml')
VTM = VirtualTopologyManager('../topologies/mmm_virtual_test_qos.yaml')
BM = BindingManager(PTM, VTM)

bindings1 = {
    'description': 'ports spanning computes and networks',
    'bindings': [
        {'binding':
             {'device_name': 'bridge-000-001', 'port_id': 1,
              'host_id': 1, 'interface_id': 1}},
        {'binding':
             {'device_name': 'bridge-000-001', 'port_id': 2,
              'host_id': 2, 'interface_id': 1}},
        {'binding':
             {'device_name': 'bridge-000-002', 'port_id': 1,
              'host_id': 2, 'interface_id': 2}},
    ]
}


@attr(version="v1.2.0")
@failures(NoFailure())
@bindings(bindings1)
def test_qos_policy_update():
    """
    Title: QoS Policy update

    1) Update existing QoS policy and see the updates take effect.
    """

    pol1 = VTM.get_qos_policy("pol1")
    pol1.description = "Updated description"
    pol1.update()

    pol1b = VTM.get_qos_policy("pol1")

    assert_that(pol1b.get_description(), "Updated description")


@attr(version="v1.2.0")
@failures(NoFailure())
@bindings(bindings1)
def test_qos_bw_limit_on_port_no_burst():
    """
    Title: Port-based BW Limit, No Burst

    1) Test that setting a bandwidth limit on a port with no burst will limit
    the rate of packets on the port.

    2) Test that updating a bandwidth limit on a port with no burst will
    limit the rate of packets on the port to the new value.

    3) Test that clearing a bandwidth limit on a port with no burst will no
    longer limit the rate of packets on the port.
    """

    pol1 = VirtualTopologyManager.get_qos_policy("pol1")
    portA = VTM.get_device_port('bridge-000-001', 1)
    portA.set_qos_policy(pol1)

    sender = BM.get_iface_for_port('bridge-000-001', 1)
    assert_that(sender.verify_bandwidth(max_kbps=None, max_burst=None))

    pol1.add_bw_limit_rule({'max_kbps': 100, 'max_burst_kbps': 0})
    assert_that(sender.verify_bandwidth(max_kbps=100, max_burst=None))

    rule1 = pol1.get_bw_limit_rules[0]
    rule1.max_kbps = 400
    rule1.update()

    assert_that(sender.verify_bandwidth(max_kbps=400, max_burst=None))

    pol1.clear_bw_limit_rules()
    assert_that(sender.verify_bandwidth(max_kbps=None, max_burst=None))

    portA.clear_qos_policy()


@attr(version="v1.2.0")
@failures(NoFailure())
@bindings(bindings1)
def test_qos_bw_limit_on_port_with_burst():
    """
    Title: Port-based BW Limit With Burst
    1) Test that setting a bandwidth limit on a port with a burst will limit
    the rate of packets on the port, but allow for brief periods of high
    count.

    2) Test that clearing a bandwidth limit on a port with a burst will no
    longer limit the rate of packets on the port or limit the number of
    burst packets passing through.
    """

    pol1 = VTM.get_qos_policy("pol1")
    portA = VTM.get_device_port('bridge-000-001', 1)
    portA.set_qos_policy(pol1)

    sender = BM.get_iface_for_port('bridge-000-001', 1)
    assert_that(sender.verify_bandwidth(max_kbps=None, max_burst=None))

    pol1.add_bw_limit_rule({'max_kbps': 100, 'max_burst_kbps': 1000})
    assert_that(sender.verify_bandwidth(max_kbps=100, max_burst=1000))

    rule1 = pol1.get_bw_limit_rules[0]
    rule1.max_kbps = 400
    rule1.max_kbps = 2000
    rule1.update()

    assert_that(sender.verify_bandwidth(max_kbps=400, max_burst=2000))

    pol1.clear_bw_limit_rules()
    assert_that(sender.verify_bandwidth(max_kbps=None, max_burst=None))

    portA.clear_qos_policy()


@attr(version="v1.2.0")
@failures(NoFailure())
@bindings(bindings1)
def test_qos_bw_limit_on_port_with_network_policy():
    """
    1) Test that setting bandwidth limit/burst on a port belonging to a
    network which has a bandwidth rule already in place (and in effect)
    will override that network-based rule and instead apply the specific
    port policy's bandwidth limit/burst to the traffic on the port.

    2) Test that clearing bandwidth limit/burst on a port belonging to a
    network which has a bandwidth rule set will result in the port's
    traffic being forced to follow the network policy's bandwidth
    limit/burst rules.
    """

    polPort = VTM.get_qos_policy("pol1")
    polNet = VTM.get_qos_policy("pol2")

    portA = VTM.get_device_port('bridge-000-001', 1)
    bridgeA = VTM.get_bridge('bridge-000-001')

    polNet.add_bw_limit_rule({'max_kbps': 200, 'max_burst_kbps': 2000})

    # This should override pol2's settings, meaning portA, which uses
    # pol1, should act according to pol1's settings, NOT the pol2 on the
    # network
    polPort.add_bw_limit_rule({'max_kbps': 100, 'max_burst_kbps': 1000})

    bridgeA.set_qos_policy(polNet)
    portA.set_qos_policy(polPort)

    sender = BM.get_iface_for_port('bridge-000-001', 1)
    assert_that(sender.verify_bandwidth(max_kbps=100, max_burst=1000))

    # But if we remove portA's association to pol1, the behavior should
    # then fall back to the network's policy: pol2
    portA.clear_qos_policy()

    assert_that(sender.verify_bandwidth(max_kbps=200, max_burst=2000))

    bridgeA.clear_qos_policy()

    assert_that(sender.verify_bandwidth(max_kbps=None, max_burst=None))

    polNet.clear_bw_limit_rules()
    polPort.clear_bw_limit_rules()
