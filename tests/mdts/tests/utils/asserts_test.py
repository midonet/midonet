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
        self.assertEquals('icmp and src host 100.100.100.100 and '
                          'icmp[20:4] = 2886729985 and '
                          'icmp[24:4] = 1684300900 and '
                          'icmp[28:2] = 9 and icmp[30:2] = 9',
                          ifExpects._filter)
        self.assertEquals(5,
                          ifExpects._timeout)


if __name__ == "__main__":
    unittest.main()