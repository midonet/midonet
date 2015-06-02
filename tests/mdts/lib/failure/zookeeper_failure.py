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
import subprocess
import time

from mdts.lib import subprocess_compat
from mdts.lib.failure.failure_base import FailureBase
from mdts.tests.utils import check_all_zookeeper_hosts
from mdts.tests.utils import check_zookeeper_host

LOG = logging.getLogger(__name__)

class ZookeeperFailure(FailureBase):
    """Emulate zookeeper failure by setting the interface down

    @netns      network namespace name
    @interface  interface name
    @ip         ip of the zookeeper node
    """
    def __init__(self, netns, interface, ip):
        super(ZookeeperFailure, self).__init__("netif_failure %s %s" %
                                           (netns, interface))
        self._netns = netns
        self._interface = interface
        self._ip = ip

    def inject(self):
        cmdline =  "ip netns exec %s ip link set dev %s down"  \
            % (self._netns, self._interface)
        LOG.debug('set dev %s down in %s' \
                      % (self._interface, self._netns))
        subprocess_compat.check_output(cmdline.split())
        # check the zk host actually went down
        check_zookeeper_host(self._ip, alive=False)

    def eject(self):
        cmdline =  "ip netns exec %s ip link set dev %s up"  \
            % (self._netns, self._interface)
        LOG.debug('set dev %s up in %s' \
                      % (self._interface, self._netns))
        subprocess_compat.check_output(cmdline.split())
        # Check the zk host actually went up and in quorum
        check_zookeeper_host(self._ip, alive=True)

