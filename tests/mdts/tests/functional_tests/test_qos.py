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

from mdts.lib.physical_topology_manager import TopologyManager
from mdts.lib.virtual_topology_manager import VirtualTopologyManager
from mdts.services import service
from mdts.tests.utils.asserts import *
from mdts.tests.utils import utils
from nose.plugins.attrib import attr
from nose.tools import nottest


class QosTopology(VirtualTopologyManager):

    def __init__(self, filename=None, data=None, midonet_api=None):
        super(QosTopology, self).__init__(filename, data, midonet_api)
        self.main_bridge = None
        self.vm1 = None
        self.vm2 = None
        self.vm1_port = None
        self.vm2_port = None
        self.qos_pol1 = None
        self.qos_pol2 = None

    def build(self, binding_data=None, ptm=None):
        super(QosTopology, self).build(binding_data)

        ptm.add_host_to_tunnel_zone('midolman1', 'tztest1')
        ptm.add_host_to_tunnel_zone('midolman2', 'tztest1')

        self.add_bridge({'name': 'main1'})
        self.main_bridge = self.get_bridge('main1')
        self.main_bridge.add_dhcp_subnet(
            {'id': 1,
             'ipv4_gw': '172.16.1.254',
             'network': '172.16.1.0/24'})

        self.main_bridge.add_port({'id': 1, 'type': 'exterior'})
        self.main_bridge.add_port({'id': 2, 'type': 'exterior'})
        self.vm1_port = self.main_bridge.get_port(1)
        self.vm2_port = self.main_bridge.get_port(2)

        host1 = service.get_container_by_hostname('midolman1')
        host2 = service.get_container_by_hostname('midolman2')

        vm1data = {'hw_addr': 'aa:bb:cc:00:00:11',
                   'ipv4_addr': ['172.16.1.2/24'],
                   'ipv4_gw': '172.16.1.1'}
        vm2data = {'hw_addr': 'aa:bb:cc:00:00:22',
                   'ipv4_addr': ['172.16.1.3/24'],
                   'ipv4_gw': '172.16.1.1'}

        self.vm1 = host1.create_vmguest(**vm1data)
        self.vm2 = host2.create_vmguest(**vm2data)

        ptm.addCleanup(host1.destroy_vmguest, self.vm1)
        ptm.addCleanup(host2.destroy_vmguest, self.vm2)

        port1_real_id = self.vm1_port.get_real_id()
        port2_real_id = self.vm2_port.get_real_id()

        host1.bind_port(self.vm1, port1_real_id)
        host2.bind_port(self.vm2, port2_real_id)

        utils.await_port_active(port1_real_id, active=True)
        utils.await_port_active(port2_real_id, active=True)

        self.qos_pol1 = self.add_qos_policy(
            {'name': 'pol1', 'description': 'Description',
             'shared': True})
        self.qos_pol2 = self.add_qos_policy(
            {'name': 'pol2', 'description': 'Description Two',
             'shared': True})

        assert_that(self.qos_pol1)
        assert_that(self.qos_pol2)


PTM = TopologyManager()
VTM = QosTopology(data={'virtual_topology': {}})


@nottest
def qos_test_fixture(func):
    def new_f():
        try:
            PTM.build()
            VTM.build(ptm=PTM)
            func()
        finally:
            VTM.destroy()
            PTM.destroy()
    new_f.__name__ = func.__name__
    return new_f


@qos_test_fixture
@attr(version="v1.2.0")
def test_qos_policy_update():
    """
    Title: QoS Policy update

    1) Update existing QoS policy and see the updates take effect.
    """
    VTM.qos_pol1.description("Updated description")

    assert_that(VTM.qos_pol1.get_mn_resource().get_description() ==
                "Updated description")


@qos_test_fixture
@attr(version="v1.2.0")
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

    VTM.vm1_port.set_qos_policy(VTM.qos_pol1)

    assert_that(VTM.vm1.verify_bandwidth(target_iface=VTM.vm2,
                                         max_kbps=1000, burst_kb=5000,
                                         allowed_lead=1.0))

    VTM.qos_pol1.add_bw_limit_rule({'max_kbps': 100, 'max_burst_kbps': 1000})
    assert_that(VTM.vm1.verify_bandwidth(target_iface=VTM.vm2,
                                         max_kbps=100, burst_kb=1000))

    rule1 = VTM.qos_pol1.get_bw_limit_rules()[0]
    rule1.max_kbps = 400
    rule1.max_kbps = 2000
    rule1.update()

    assert_that(VTM.vm1.verify_bandwidth(target_iface=VTM.vm2,
                                         max_kbps=400, burst_kb=2000))

    VTM.qos_pol1.clear_bw_limit_rules()
    assert_that(VTM.vm1.verify_bandwidth(target_iface=VTM.vm2,
                                         max_kbps=1000, burst_kb=5000,
                                         allowed_lead=1.0))

    VTM.vm1_port.clear_qos_policy()


@qos_test_fixture
@attr(version="v1.2.0")
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

    VTM.qos_pol2.add_bw_limit_rule({'max_kbps': 200, 'max_burst_kbps': 2000})

    # This should override pol2's settings, meaning portA, which uses
    # pol1, should act according to pol1's settings, NOT the pol2 on the
    # network
    VTM.qos_pol1.add_bw_limit_rule({'max_kbps': 100, 'max_burst_kbps': 1000})

    VTM.main_bridge.set_qos_policy(VTM.qos_pol2)
    VTM.vm1_port.set_qos_policy(VTM.qos_pol1)

    assert_that(VTM.vm1.verify_bandwidth(target_iface=VTM.vm2,
                                         max_kbps=100, burst_kb=1000))

    # But if we remove portA's association to pol1, the behavior should
    # then fall back to the network's policy: pol2
    VTM.vm1_port.clear_qos_policy()

    assert_that(VTM.vm1.verify_bandwidth(target_iface=VTM.vm2,
                                         max_kbps=200, burst_kb=2000))

    VTM.main_bridge.clear_qos_policy()

    assert_that(VTM.vm1.verify_bandwidth(target_iface=VTM.vm2,
                                         max_kbps=1000, burst_kb=5000,
                                         allowed_lead=1.0))

    VTM.qos_pol2.clear_bw_limit_rules()
    VTM.qos_pol1.clear_bw_limit_rules()
