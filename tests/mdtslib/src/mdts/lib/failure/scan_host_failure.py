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

from mdts.lib.failure.failure_base import FailureBase

LOG = logging.getLogger(__name__)


class ScanHostFailure(FailureBase):
    """Emulate host/port scan using nmap

    @netns      network namespace name
    @target     target in tuple (host range, port range)
                * range can be any string nmap accepts, for instance,
                  ('172.16.0.1-254', '80')
                * See TARGET SPECIFICATION in nmap(1)
    @timing     0-5 [higher is faster]
                * See -T in nmap(1)

    NOTE: work in progress
    """
    def __init__(self, netns, target, timing=4):
        super(ScanHostFailure, self)\
            .__init__("scan_host_failure %s %s %s" % (netns, target, timing))
        self._netns = netns
        self._target = target
        self._timing = timing

    def inject(self):
        cmdline = ['ip', 'netns', 'exec',
                   self._netns,
                   'nmap',
                   '-PN',
                   '-r',
                   '-T%d' % self._timing,
                   '-p', self._target[1],
                   self._target[0]]
        LOG.debug('running %r' % (cmdline,))
        self._process = subprocess.Popen(cmdline, stdout=subprocess.PIPE)

    def eject(self):
        self._process.kill()
