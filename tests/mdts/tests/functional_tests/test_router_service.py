# Copyright 2015 Midokura SARL
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

import logging
from mdts.lib.binding_manager import BindingManager
from mdts.lib.physical_topology_manager import PhysicalTopologyManager
from mdts.lib.virtual_topology_manager import VirtualTopologyManager
from mdts.tests.utils.asserts import async_assert_that
from mdts.tests.utils.asserts import receives
from mdts.tests.utils.asserts import should_NOT_receive
from mdts.tests.utils.asserts import within_sec
from mdts.tests.utils.utils import bindings
from mdts.tests.utils.utils import wait_on_futures
from nose.plugins.attrib import attr

LOG = logging.getLogger(__name__)

PTM = PhysicalTopologyManager('../topologies/mmm_physical_test_router_service.yaml')
VTM = VirtualTopologyManager('../topologies/mmm_virtual_test_router_service.yaml')
BM = BindingManager(PTM, VTM)

binding_multihost = {
    'description': 'spanning across multiple MMs',
    'bindings': [
        {'binding':
            {'device_name': 'bridge-000-001', 'port_id': 2,
             'host_id': 1, 'interface_id': 1}},
        {'binding':
            {'device_name': 'router-000-001', 'port_id': 2,
             'host_id': 1, 'interface_id': 2}},
        {'binding':
            {'device_name': 'router-000-001', 'port_id': 3,
             'host_id': 2, 'interface_id': 3}},
    ]
}


@attr(version="v1.2.0")
@bindings(binding_multihost)
def test_router_service():
    """
    Title: TCP/UDP services on router

    Scenario 1:
    When: A client tries to access a TCP or UDP service on a router.
    Then: the router should redirect the traffic to a namespace.
    And: the namespace should respond correctly.
    """

    router = VTM.get_router('router-000-001')
    pre_filter = VTM.get_chain('pre_filter_001')
    router.set_local_redirect_chain(pre_filter)

    sender1 = BM.get_iface_for_port('bridge-000-001', 2)

    service1 = BM.get_iface_for_port('router-000-001', 2)
    service2 = BM.get_iface_for_port('router-000-001', 3)

    # setup extra parameters in namespaces
    service1.execute("ip a add 172.16.1.254/32 dev lo")
    service1.execute("arp -s 169.254.1.1 aa:bb:cc:00:11:33")
    service2.execute("ip a add 172.16.1.254/32 dev lo")
    service2.execute("arp -s 169.254.2.1 aa:bb:cc:00:11:33")

    # assert udp traffic goes to correct node
    f1 = async_assert_that(service1,
                           receives('dst host 172.16.1.254 and udp port 500',
                                    within_sec(5)))
    f2 = async_assert_that(service2,
                           should_NOT_receive('dst host 172.16.1.2 and udp port 500',
                                              within_sec(5)))
    f3 = sender1.send_udp('aa:bb:cc:00:00:22', '172.16.1.254', 41,
                          src_port=500, dst_port=500)
    wait_on_futures([f1, f2, f3])

    # assert udp traffic is returned
    f1 = async_assert_that(sender1,
                           receives('dst host 172.16.1.1 and udp port 500',
                                    within_sec(5)))
    f2 = service1.send_udp('aa:bb:cc:00:11:22', '172.16.1.1', 41,
                           src_port=500, dst_port=500,
                           src_hw='aa:bb:cc:00:00:22', src_ipv4='172.16.1.254')
    wait_on_futures([f1, f2])

    # assert tcp traffic goes both ways
    try:
        service2.execute("sh -c \"echo foobar | nc -l 1500\"")
        f1 = async_assert_that(service2,
                               receives('dst host 172.16.1.254 and tcp port 1500',
                                        within_sec(5)))
        f2 = async_assert_that(sender1,
                               receives('dst host 172.16.1.1 and tcp port 1500',
                                        within_sec(5)))
        f3 = async_assert_that(service1,
                               should_NOT_receive('dst host 172.16.1.254',
                                                  within_sec(5)))
        sender1.execute("sh -c \"echo foobar | nc 172.16.1.254 1500\"")
        wait_on_futures([f1, f2, f3])
    finally:
        service2.execute("pkill nc")
