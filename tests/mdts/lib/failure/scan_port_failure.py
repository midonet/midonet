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

class ScanPortFailure(FailureBase):
    """Emulate port scan using mz

    @netns      network namespace name
    @interface  interface name
    @src        src in tuple (host, port range)
    @dst        dst in tuple (host, port range)
                * range can be any string mz accepts,
                  for instance, '1-65535'
    @delay      any string mz accepts; in usec by default
                * See -d in mz(1)

    NOTE: work in progress
    """
    def __init__(self, netns, interface, src, dst, delay = '0', count = 0):
        super(ScanPortFailure, self)\
            .__init__("scan_port_failure %s %s %s %s %s %d" \
                      % (netns, interface, src, dst, delay, count))
        self._netns = netns
        self._interface = interface
        self._proto = 'udp'
        self._src = src
        self._dst = dst
        self._delay = delay
        self._count = count

    def inject(self):
        cmdline = ['ip', 'netns', 'exec',
                   self._netns,
                   'mz',
                   self._interface,
                   '-c', '%d' % self._count,
                   '-d', self._delay,
                   '-A', self._src[0],
                   '-B', self._dst[0],
                   '-t', self._proto,
                   'sp=%s,dp=%s' % (self._src[1], self._dst[1])]
        LOG.debug('running %r' % (cmdline,))
        self._process = subprocess.Popen(cmdline, stdout=subprocess.PIPE)

    def eject(self):
        self._process.kill()
