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

class PktFailure(FailureBase):
    """Emulate network failure filtering packets using iptables

    @netns      network namespace name
    @interface  interface name
    @wait       sleep after set down/up the interface (in sec)

    NOTE: drop only incoming packets for now
    """
    def __init__(self, netns, interface, wait=10):
        super(PktFailure, self).__init__("pkt_failure %s %s" % \
                                             (netns, interface))
        self._netns = netns
        self._interface = interface
        self._wait = wait

    def inject(self):
        cmdline = "ip netns exec %s iptables -i %s -A INPUT -j DROP" \
            % (self._netns, self._interface)
        LOG.debug('drop packets coming from %s in %s' \
                      % (self._interface, self._netns))
        subprocess_compat.check_output(cmdline.split())
        time.sleep(self._wait)

    def eject(self):
        cmdline = "ip netns exec %s iptables -i %s -D INPUT -j DROP"  \
            % (self._netns, self._interface)
        LOG.debug('take packets coming from %s in %s' \
                      % (self._interface, self._netns))
        subprocess_compat.check_output(cmdline.split())
        time.sleep(self._wait)
