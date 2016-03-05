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
from mdts.lib.physical_topology_manager import PhysicalTopologyManager
from mdts.lib.virtual_topology_manager import VirtualTopologyManager
from mdts.lib.binding_manager import BindingManager
from mdts.tests.utils.asserts import *
from mdts.tests.utils.utils import bindings
from mdts.tests.utils.utils import wait_on_futures

from hamcrest import *
from nose.tools import nottest

import logging
import time
import pdb
import re
import subprocess


LOG = logging.getLogger(__name__)

PTM = PhysicalTopologyManager('../topologies/mmm_physical_perf_flowstate.yaml')
VTM = VirtualTopologyManager('../topologies/mmm_virtual_perf_flowstate.yaml')
BM = BindingManager(PTM, VTM)

binding_multihost = {
    'description': 'spanning across multiple MMs',
    'bindings': [
        {'binding':
             {'device_name': 'bridge-000-001', 'port_id': 2,
              'host_id': 1, 'interface_id': 1}},
        {'binding':
             {'device_name': 'bridge-000-002', 'port_id': 2,
              'host_id': 2, 'interface_id': 1}},
        ]
    }


@attr(version="v1.2.0", slow=True, flaky=False)
@bindings(binding_multihost)
def perf_flowstate():
    """
    Title: 

    Scenario 1:
    When: a VM sends ICMP echo request with ping command to a different subnet
    Then: the receiver VM should receive the ICMP echo packet.
    And: the ping command succeeds
    """
    router = VTM.get_router('router-000-001')
    prefilter = VTM.get_chain('pre_filter_001')
    postfilter = VTM.get_chain('post_filter_001')
    router.set_inbound_filter(prefilter)
    router.set_outbound_filter(postfilter)

    sender = BM.get_iface_for_port('bridge-000-001', 2)
    receiver = BM.get_iface_for_port('bridge-000-002', 2)

    messages_per_second = 10
    delay = 1000000/messages_per_second
    try:
        sender.execute("hping3 --rand-source -2 -i u%d %s" % (delay,
                                                          receiver.get_ip()))
        rcv_filter = 'udp and ip dst %s' % (receiver.get_ip())
        # check that we are still receiving traffic every minute for 60 minutes
        for i in range(0, 60):
            assert_that(receiver,
                        receives(rcv_filter, within_sec(10)))
            time.sleep(60)
    finally:
        sender.execute("pkill hping3")


