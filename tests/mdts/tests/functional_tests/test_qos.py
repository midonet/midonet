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
from mdts.lib.virtual_topology_manager import VirtualTopologyManager
from mdts.lib.binding_manager import BindingManager
from mdts.lib.failure.no_failure import NoFailure
from mdts.tests.utils.asserts import *
from nose.plugins.attrib import attr
from mdts.tests.utils.utils import bindings
from mdts.tests.utils.utils import failures
from mdts.tests.utils.utils import wait_on_futures

from hamcrest import *

import logging

LOG = logging.getLogger(__name__)

PTM = PhysicalTopologyManager('../topologies/mmm_physical_test_qos.yaml')
VTM = VirtualTopologyManager('../topologies/mmm_virtual_test_qos.yaml')
BM = BindingManager(PTM, VTM)

bindings1 = {
    'description': 'spanning across two MM',
    'bindings': [
        {'binding':
             {'device_name': 'bridge-000-001', 'port_id': 1,
              'host_id': 1, 'interface_id': 1}},
        {'binding':
             {'device_name': 'bridge-000-001', 'port_id': 2,
              'host_id': 1, 'interface_id': 2}},
    ]
}

@attr(version="v1.2.0")
@failures(NoFailure())
@bindings(bindings1)
def test_bw_limit():
    sender = BM.get_iface_for_port('bridge-000-001', 1)
    receiver = BM.get_iface_for_port('bridge-000-001', 2)

    f1 = async_assert_that(receiver,
                           receives('dst host %s and icmp' % receiver.get_ip(),
                                    within_sec(5)))
    f2 = sender.ping4(receiver)

    wait_on_futures([f1, f2])
