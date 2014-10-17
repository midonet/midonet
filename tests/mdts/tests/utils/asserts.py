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

from concurrent.futures import ThreadPoolExecutor

from hamcrest import assert_that
from hamcrest.core.base_matcher import BaseMatcher

from mdts.tests.utils.utils import ipv4_int

import logging


LOG = logging.getLogger(__name__)
NUM_WORKERS = 10
EXECUTOR = ThreadPoolExecutor(max_workers=NUM_WORKERS)


class InterfaceExpects(BaseMatcher):

    def __init__(self, expected, pcap_filter_string, timeout):
        self._expected = expected
        self._filter = pcap_filter_string
        self._timeout = timeout # in sec
        self._iface = None

    def _matches(self, iface):
        result = iface.expect(self._filter, self._timeout).result()
        return result == self._expected

    def describe_to(self, description):
        description.append_text('should not ' if not self._expected else '')\
                   .append_text('receives packet with tcpdump by ')\
                   .append_text('filter=%r' % self._filter )\
                   .append_text('within %d sec' % self._timeout)

    def describe_mismatch(self, item, mismatch_description):
        mismatch_description.append_description_of(item)\
                            .append_text("did")\
                            .append_text(" not" if self._expected else "")

def receives(pcap_filter_string, timeout=3):
    return InterfaceExpects(True, pcap_filter_string, timeout)

def should_NOT_receive(pcap_filter_string, timeout=3):
    return InterfaceExpects(False, pcap_filter_string, timeout)

def receives_icmp_unreachable_for_udp(udp_src_ip,
                                      udp_dst_ip,
                                      udp_src_port=9,
                                      udp_dst_port=9,
                                      timeout=5):
    """ Receives an ICMP unreachable reply for a UDP packet.

    Constructs pcap filter strings for an ICMP unreachable reply received in
    response for a UDP packet sent.

    Args:
        udp_src_ip: Source IP address for the original UDP packet.
        udp_dst_ip: Destination IP address for the original UDP packet.
        udp_src_port: Source port for the original UDP packet.
        udp_dst_port: Destination port for the original UDP packet.
    """
    pcap_filter_string = (
            'icmp and src host %s and '
            'icmp[20:4] = %d and icmp[24:4] = %d and '
            'icmp[28:2] = %d and icmp[30:2] = %d') % (
            udp_dst_ip,
            ipv4_int(udp_src_ip),
            ipv4_int(udp_dst_ip),
            udp_src_port,
            udp_dst_port)
    return InterfaceExpects(True, pcap_filter_string, timeout)


def async_assert_that(*args):
    """ Returns future of assert_that(*args)"""
    return EXECUTOR.submit(assert_that, *args)


def within_sec(sec):
    return sec
