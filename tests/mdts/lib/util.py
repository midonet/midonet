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

"""
Provides utility functions.
"""


def ping4_cmd(ipv4_addr, interval=0.5, count=3, size=56):
    """Constructs a ping command line to an IPv4 address."""
    return 'ping -i {0} -c {1} -s {2} {3}'.format(interval,
                                                  count,
                                                  size,
                                                  ipv4_addr)