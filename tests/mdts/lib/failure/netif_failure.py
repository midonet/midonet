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

import logging
import subprocess
import time

from mdts.lib import subprocess_compat
from mdts.lib.failure.failure_base import FailureBase

LOG = logging.getLogger(__name__)

class NetifFailure(FailureBase):
    """Emulate network interface failure setting the interface down

    @netns      network namespace name
    @interface  interface name
    @wait       sleep after set down/up the interface (in sec)
    """
    def __init__(self, netns, interface, wait=10):
        super(NetifFailure, self).__init__("netif_failure %s %s" %
                                           (netns, interface))
        self._netns = netns
        self._interface = interface
        self._wait = wait

    def inject(self):
        cmdline =  "ip netns exec %s ip link set dev %s down"  \
            % (self._netns, self._interface)
        LOG.debug('set dev %s down in %s' \
                  % (self._interface, self._netns))
        subprocess_compat.check_output(cmdline.split())
        time.sleep(self._wait)

    def eject(self):
        cmdline =  "ip netns exec %s ip link set dev %s up"  \
            % (self._netns, self._interface)
        LOG.debug('set dev %s up in %s' \
                  % (self._interface, self._netns))
        subprocess_compat.check_output(cmdline.split())
        time.sleep(self._wait)
