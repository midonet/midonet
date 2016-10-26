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

import string
import sys

import logging
from mdts.lib.failure.failure_base import FailureBase

LOG = logging.getLogger(__name__)

class CombinedFailure(FailureBase):
    """Emulate multiple failures that happen simultaneously

    @failures   failure list

    """
    def __init__(self, failures):
        super(CombinedFailure, self).__init__(
            string.join(map(lambda x: x.__name__, failures), " and "))
        self._failures = failures

    def inject(self):
        for failure in self._failures:
            failure.inject()

    def eject(self):
        for failure in reversed(self._failures):
            try:
                failure.eject()
            except:
                LOG.exception(sys.exc_info()[1])
