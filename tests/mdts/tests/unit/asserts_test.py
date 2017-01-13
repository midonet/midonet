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
Unit tests for asserts.py
"""
from mdts.tests.utils.asserts import receives_icmp_unreachable_for_udp
import unittest


class AssertsTest(unittest.TestCase):

    def test_receives_icmp_unreachable_for_udp(self):
        ifExpects = receives_icmp_unreachable_for_udp('172.16.1.1',
                                                      '100.100.100.100',
                                                      9, 9, 5)
        self.assertEqual('icmp and src host 100.100.100.100 and '
                         'icmp[20:4] = 2886729985 and '
                         'icmp[24:4] = 1684300900 and '
                         'icmp[28:2] = 9 and icmp[30:2] = 9',
                         ifExpects._filter)
        self.assertEqual(5,
                         ifExpects._timeout)


if __name__ == "__main__":
    unittest.main()
