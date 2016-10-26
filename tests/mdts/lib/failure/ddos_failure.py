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

class DDoSFailure(FailureBase):
    """Emulate DDoS attach using bonesi

    @netns      network namespace name
    @interface  interface name
    @target     destination ip address and port number (e.g. '100.0.0.1:80')
    @rate       packets per second (0 = infinite)

    SEE ALSO: http://code.google.com/p/bonesi/

    TODO: bonesi executable is not included in the commit
    """

    base_path = 'bonesi-0.2.0'

    def __init__(self, netns, interface, target, rate=1):
        super(DDoSFailure, self).__init__("ddos_failure %s %s %s" \
                                          % (netns, interface, target))
        self._netns = netns
        self._interface = interface
        self._target = target
        self._rate = rate

    def inject(self):
        cmdline = ['ip', 'netns', 'exec',
                   self._netns,
                   DDoSFailure.base_path + '/src/bonesi',
                   '-p', 'tcp',
                   '-r', str(self._rate),
                   '-u', '/',
                   '-i', DDoSFailure.base_path + '/50k-bots',
                   '-d', self._interface,
                   self._target]
        LOG.debug('running %r' % (cmdline,))
        self._process = subprocess.Popen(cmdline, stdout=subprocess.PIPE)

    def eject(self):
        self._process.kill()
