"""
Unit tests for mdts.tests.utils.utils module.
"""
from mdts.tests.utils.utils import ipv4_int

import unittest


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


if __name__ == "__main__":
    unittest.main()