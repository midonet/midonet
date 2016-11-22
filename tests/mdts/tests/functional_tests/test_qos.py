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

from mdts.lib.physical_topology_manager import TopologyManager
from mdts.lib.virtual_topology_manager import VirtualTopologyManager
from mdts.services import service
from mdts.tests.utils.asserts import assert_that
from mdts.tests.utils import utils


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


def test_qos_policy_update():
    """
    Title: QoS Policy update

    1) Update existing QoS policy and see the updates take effect.
    """
    try:
        PTM.build()
        VTM.build(ptm=PTM)
        VTM.qos_pol1.description("Updated description")

        assert_that(VTM.qos_pol1.get_mn_resource().get_description() ==
                    "Updated description")

    finally:
        VTM.destroy()
        PTM.destroy()


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
    try:
        PTM.build()
        VTM.build(ptm=PTM)

        VTM.vm1_port.set_qos_policy(VTM.qos_pol1)

        VTM.vm1.verify_bandwidth(target_iface=VTM.vm2,
                                 max_kbps=1000, burst_kb=5000,
                                 allowed_lead=1.0)

        VTM.qos_pol1.add_bw_limit_rule({'max_kbps': 100,
                                        'max_burst_kbps': 1000})
        VTM.vm1.verify_bandwidth(target_iface=VTM.vm2,
                                 max_kbps=100, burst_kb=1000)

        rule1 = VTM.qos_pol1.get_bw_limit_rules()[0]
        rule1.max_kbps(400)
        rule1.max_burst_kbps(2000)
        rule1.update()

        VTM.vm1.verify_bandwidth(target_iface=VTM.vm2,
                                 max_kbps=400, burst_kb=2000)

        VTM.qos_pol1.clear_bw_limit_rules()
        VTM.vm1.verify_bandwidth(target_iface=VTM.vm2,
                                 max_kbps=1000, burst_kb=5000,
                                 allowed_lead=1.0)

        VTM.vm1_port.clear_qos_policy()
    finally:
        VTM.destroy()
        PTM.destroy()


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

    try:
        PTM.build()
        VTM.build(ptm=PTM)

        VTM.qos_pol2.add_bw_limit_rule({'max_kbps': 200,
                                        'max_burst_kbps': 2000})

        # This should override pol2's settings, meaning portA, which uses
        # pol1, should act according to pol1's settings, NOT the pol2 on the
        # network
        VTM.qos_pol1.add_bw_limit_rule({'max_kbps': 100,
                                        'max_burst_kbps': 1000})

        VTM.main_bridge.set_qos_policy(VTM.qos_pol2)
        VTM.vm1_port.set_qos_policy(VTM.qos_pol1)

        VTM.vm1.verify_bandwidth(target_iface=VTM.vm2,
                                 max_kbps=100, burst_kb=1000)

        # But if we remove portA's association to pol1, the behavior should
        # then fall back to the network's policy: pol2
        VTM.vm1_port.clear_qos_policy()

        VTM.vm1.verify_bandwidth(target_iface=VTM.vm2,
                                 max_kbps=200, burst_kb=2000)

        VTM.main_bridge.clear_qos_policy()

        VTM.vm1.verify_bandwidth(target_iface=VTM.vm2,
                                 max_kbps=1000, burst_kb=5000,
                                 allowed_lead=1.0)

        VTM.qos_pol2.clear_bw_limit_rules()
        VTM.qos_pol1.clear_bw_limit_rules()

    finally:
        VTM.destroy()
        PTM.destroy()


def test_qos_dscp_mark_on_port():
    """
    1) Test that setting a DSCP rule on a port will transform all
    default packets that ingress the cloud network through that port
    to set the IP DS header to the given DSCP mark, and reset the IP
    DS header to the given DSCP mark, if already present.

    2) Test that clearing a DSCP rule on a port will no longer set
    the IP DS header on default packets, nor change the IP DS header
    on packets with DS header already set.
    """

    try:
        PTM.build()
        VTM.build(ptm=PTM)
        VTM.vm1_port.set_qos_policy(VTM.qos_pol1)

        VTM.vm1.verify_packet_field(
            target_iface=VTM.vm2,
            field='tos', value=None)

        VTM.qos_pol1.add_dscp_rule({'dscp_mark': 22})

        VTM.vm1.verify_packet_field(
            target_iface=VTM.vm2,
            field='tos', value=22)

        rule1 = VTM.qos_pol1.get_dscp_rules()[0]
        rule1.dscp_mark = 44
        rule1.update()

        VTM.vm1.verify_packet_field(
            target_iface=VTM.vm2,
            field='tos', value=44)

        VTM.qos_pol1.clear_dscp_rules()

        VTM.vm1.verify_packet_field(
            target_iface=VTM.vm2,
            field='tos', value=None)

        VTM.vm1_port.clear_qos_policy()
    finally:
        VTM.destroy()
        PTM.destroy()


def test_qos_dscp_mark_on_network():
    """
    1) Test that setting a DSCP rule on a network will transform all
    default packets that ingress the cloud on the network to set the IP
    DS headers to the given DSCP mark, or reset the IP DS headers to the
    given DSCP mark, if already set.

    2) Test that clearing a DSCP rule on a network will no longer set
    the IP DS header on default, and will no longer change the IP DS
    header on packets with DS header already set.

    3) Test that setting and clearing the DSCP rule on a network will also
    affect any traffic on any new ports created on that network.
    """

    try:
        PTM.build()
        VTM.build(ptm=PTM)
        VTM.qos_pol1.add_dscp_rule({'dscp_mark': 11})

        VTM.main_bridge.set_qos_policy(VTM.qos_pol1)

        VTM.vm1.verify_packet_field(
            target_iface=VTM.vm2,
            field='tos', value=11)

        VTM.main_bridge.add_port({'id': 3, 'type': 'exterior'})
        vm3_port = VTM.main_bridge.get_port(3)
        host1 = service.get_container_by_hostname('midolman1')
        vm3data = {'hw_addr': 'aa:bb:cc:00:00:11',
                   'ipv4_addr': ['172.16.1.4/24'],
                   'ipv4_gw': '172.16.1.1'}
        vm3 = host1.create_vmguest(**vm3data)
        port3_real_id = vm3_port.get_real_id()
        host1.bind_port(vm3, port3_real_id)
        utils.await_port_active(port3_real_id, active=True)

        vm3.verify_packet_field(
            target_iface=VTM.vm2,
            field='tos', value=11)

        VTM.main_bridge.clear_qos_policy()

        VTM.vm1.verify_packet_field(
            target_iface=VTM.vm2,
            field='tos', value=None)

        vm3.verify_packet_field(
            target_iface=VTM.vm2,
            field='tos', value=None)

        VTM.qos_pol2.clear_dscp_rules()
        VTM.qos_pol1.clear_dscp_rules()

        host1.destroy_vmguest(vm3)
    finally:
        VTM.destroy()
        PTM.destroy()


def test_qos_dscp_mark_on_port_with_network_policy():
    """
    1) Test that setting a DSCP rule on a network with ports that have
    specific DSCP rules set will apply the network's rules to other ports
    with no specific policy, but those ports with specific policy rules
    set, the traffic on those ports would be unaffected.

    2) Test that clearing the DSCP rule on a network with ports that
    have specific DSCP rules set will no longer apply rules to other
    ports with no specific policy, but those ports with specific policy
    rules set, the traffic on those ports would be unaffected.
    """

    try:
        PTM.build()
        VTM.build(ptm=PTM)
        VTM.qos_pol2.add_dscp_rule({'dscp_mark': 66})

        # This should override pol2's settings, meaning portA, which uses
        # pol1, should act according to pol1's settings, NOT the pol2 on the
        # network
        VTM.qos_pol1.add_dscp_rule({'dscp_mark': 88})

        VTM.main_bridge.set_qos_policy(VTM.qos_pol2)
        VTM.vm1_port.set_qos_policy(VTM.qos_pol1)

        VTM.vm1.verify_packet_field(
            target_iface=VTM.vm2,
            field='tos', value=88)

        # But if we remove portA's association to pol1, the behavior should
        # then fall back to the network's policy: pol2
        VTM.vm1_port.clear_qos_policy()

        VTM.vm1.verify_packet_field(
            target_iface=VTM.vm2,
            field='tos', value=66)

        VTM.main_bridge.clear_qos_policy()

        VTM.vm1.verify_packet_field(
            target_iface=VTM.vm2,
            field='tos', value=None)

        VTM.qos_pol2.clear_dscp_rules()
        VTM.qos_pol1.clear_dscp_rules()
    finally:
        VTM.destroy()
        PTM.destroy()
