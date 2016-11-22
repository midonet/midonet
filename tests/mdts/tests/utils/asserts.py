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
import logging
from mdts.tests.utils.utils import ipv4_int

LOG = logging.getLogger(__name__)
NUM_WORKERS = 10
EXECUTOR = ThreadPoolExecutor(max_workers=NUM_WORKERS)


class InterfaceExpects(BaseMatcher):

    def __init__(self, expected, pcap_filter_string, timeout,
                 listen_host_interface=False, count=1):
        self._expected = expected
        self._filter = pcap_filter_string
        self._timeout = timeout  # in sec
        self._listen_host_interface = listen_host_interface
        self._iface = None
        self._count = count

    def _matches(self, iface):
        self._iface = iface
        result = iface.expect(self._filter,
                              self._timeout,
                              listen_host_interface=self._listen_host_interface,
                              count=self._count).result()
        LOG.debug("[%s] " % iface +
                  "Result = " + str(result) +
                  " / Expected = " + str(self._expected))
        return result == self._expected

    def describe_to(self, description):
        description.append_text('Interface %s ' % str(self._iface))\
                   .append_text('SHOULD NOT ' if not self._expected
                                else 'SHOULD ')\
                   .append_text('receive packet with tcpdump by ')\
                   .append_text('filter=%r ' % self._filter)\
                   .append_text('within %d sec' % self._timeout)

    def describe_mismatch(self, item, mismatch_description):
        mismatch_description.append_text('Interface %s ' % str(self._iface))\
                            .append_text('did ')\
                            .append_text('not ' if self._expected else '')\
                            .append_text('receive a packet.')


def receives(pcap_filter_string, timeout=5,
             listen_host_interface=False, count=1):
    return InterfaceExpects(True,
                            pcap_filter_string,
                            timeout,
                            listen_host_interface,
                            count)


def should_NOT_receive(pcap_filter_string, timeout,
                       listen_host_interface=False, count=1):
    return InterfaceExpects(False,
                            pcap_filter_string,
                            timeout,
                            listen_host_interface,
                            count)


def receives_icmp_unreachable_for_udp(udp_src_ip,
                                      udp_dst_ip,
                                      udp_src_port=9,
                                      udp_dst_port=9,
                                      timeout=5,
                                      listen_host_interface=False):
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
    return InterfaceExpects(True, pcap_filter_string, timeout, listen_host_interface)


def async_assert_that(*args):

    iface = args[0]
    ifname = iface.get_ifname()

    """ Returns future of assert_that(*args)"""
    f = EXECUTOR.submit(assert_that, *args)

    LOG.debug('Scheduled tcpdump on interface %s' % (ifname))
    iface._tcpdump_sem.acquire()

    LOG.debug('Assert ready on interface %s' % (ifname))
    return f


def within_sec(sec):
    return sec


def on_host_interface(bool_flag):
    return bool_flag
