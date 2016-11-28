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
import time

from mdts.lib.failure.failure_base import FailureBase
from mdts.services import service

LOG = logging.getLogger(__name__)


class PktFailure(FailureBase):
    """Emulate a service failure by setting the interface down

    @netns      network namespace name
    @interface  interface name
    @ip         ip of the zookeeper node
    """
    def __init__(self, service_hostname, iface_name, wait_time=0):
        super(PktFailure, self).__init__("%s failure" % service_hostname)
        self.service_hostname = service_hostname
        self.iface_name = iface_name
        self.wait_time = wait_time
        self.service = service.get_container_by_hostname(service_hostname)

    # Maybe just make Services class to inherit from FailureBase
    def inject(self):
        self.service.inject_packet_loss(self.iface_name)
        time.sleep(self.wait_time)

    def eject(self):
        self.service.eject_packet_loss(self.iface_name)
        time.sleep(self.wait_time)
