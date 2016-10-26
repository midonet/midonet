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
Unit tests for mdts.tests.utils.utils module.
"""
from mdts.tests.utils.utils import ipv4_int
from mdts.tests.utils.utils import get_top_dir
from mdts.tests.utils.utils import get_midolman_script_dir

import unittest
import os


class UtilsTest(unittest.TestCase):

    def test_ipv4_int(self):
        """Tests ipv4_int() computes a correct IPv4 address integer."""
        self.assertEqual(2886729985, ipv4_int('172.16.1.1'))
        self.assertEqual(2886730241, ipv4_int('172.16.2.1'))
        self.assertEqual(167772687, ipv4_int('10.0.2.15'))

    def test_ipv4_int_mal_formed(self):
        """Tests ipv4_int() raises an exception for mal-formed address."""
        self.assertRaisesRegexp(Exception,
                                'Incorrect IPv4 address format: .+',
                                ipv4_int, '172.16.1')
        self.assertRaisesRegexp(Exception,
                                'Incorrect IPv4 address format: .+',
                                ipv4_int, '172.16.abc.2')

    def test_get_top_dir(self):
        top_dir = os.path.realpath(
            os.path.dirname(__file__) + '../../../')
        self.assertEquals(top_dir, get_top_dir())

    def test_get_midolman_script_dir(self):
        mm_script_dir = os.path.realpath(
            os.path.dirname(__file__) + '/../../../mmm/scripts/midolman')
        self.assertEquals(get_midolman_script_dir(), mm_script_dir)

if __name__ == "__main__":
    unittest.main()
