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

from threading import Semaphore
from concurrent.futures import ThreadPoolExecutor
from hamcrest import *
from math import ceil
import time
import logging

EXECUTOR = ThreadPoolExecutor(max_workers=10)

LOG = logging.getLogger(__name__)


class Interface(object):

    def __init__(self, compute_host=None, ifname=None, hw_addr=None,
                 ipv4_addr=None, ipv4_gw=None, mtu=1500, type=None,
                 id=None, ipv6_addr=None):
        self.compute_host = compute_host
        self.hw_addr = hw_addr
        self.ipv4_addr = ipv4_addr
        if self.ipv4_addr:
            # Get the first one from the list in the physical topology yaml
            self.ipv4_addr = ipv4_addr[0].split('/')[0]
        self.ipv4_gw = ipv4_gw
        self.cidr = None
        self.mtu = mtu
        self.num_routes = None
        self.vport_id = None
        self.ifname = ifname
        # Not used, remove in the future
        self.type = type
        self.id = id
        self.ipv6_addr = ipv6_addr
        # FIXME: hack to not modify tests in the meanwhile. Clean it up ASAP.
        self.interface = {
            'hw_addr': self.hw_addr,
            'ipv4_addr': self.ipv4_addr,
            'ipv4_gw': self.ipv4_gw,
            'mtu': self.mtu
        }
        self._tcpdump_sem = Semaphore(value=0)
        self._tcpdump_output = []
        # TODO: write all tcpdump output to a file for later inspection
        #self._tcpdump_output = ''
        #f = self.expect('', store_output=True)
        #LOG.debug('Scheduled tcpdump matching all on interface %s' % (ifname))
        #self._tcpdump_sem.acquire()

    def destroy(self):
        '''
        By default don't do anything when destroying. If specific cleaning
        is necessary, it can be done in the subclass.
        '''
        pass

    def handle_sync(funk):
        def wrapped(self, *args, **kwargs):
            future = funk(self, *args, **kwargs)
            if kwargs.get('sync'):
                return future.result()
            else:
                return future
        return wrapped

    @handle_sync
    def execute(self, cmdline, timeout=None, should_succeed=True, sync=False):
        return EXECUTOR.submit(self.do_execute, cmdline, timeout,
                               should_succeed, stream=not sync)

    @handle_sync
    def expect(self, pcap_filter_string, timeout=None, sync=False, count=1, listen_host_interface=False):
        return EXECUTOR.submit(self.do_expect, pcap_filter_string, timeout, count, listen_host_interface)

    @handle_sync
    def clear_arp(self, sync=False):
        return EXECUTOR.submit(self.do_clear_arp)

    # FIXME: the default number of packets to wait for is 1, should be configurable
    def do_expect(self, pcap_filter_string, timeout=None, count=1, listen_host_interface=False):
        """
        Expects packet with pcap_filter_string with tcpdump.
        See man pcap-filter for more details as to what you can match.


        Args:
            pcap_filter_string: capture filter to pass to tcpdump
                                See man pcap-filter
            timeout: in second

        Returns:
            True: when packet arrives
            False: when packet doesn't arrive within timeout
        """
        listen_ifname = self.get_ifname() \
            if not listen_host_interface \
            else self.get_binding_ifname()
        cmdline = 'tcpdump -n -l -i %s %s %s' % (
            listen_ifname,
            '-c %s' % count,
            pcap_filter_string)
        log_stream, exec_id = self.do_execute(cmdline,
                                              timeout,
                                              stream=True,
                                              on_netns=not listen_host_interface)
        try:
            self.compute_host.ensure_command_running(exec_id)
            LOG.debug('running tcp dump=%s', cmdline)
        except Exception:
            LOG.debug('tcpdump failed to start for some reason, '
                      'probably because interface was down.'
                      'We are not going to see any packet! %s', cmdline)
            self._tcpdump_sem.release()
        else:
            self._tcpdump_sem.release()

        try:
            LOG.debug('tcp dump running OK')
            # FIXME: wrap it in a function so we don't access members directly
            LOG.debug('Gathering results from stream of %s...' % cmdline)
            result = ""
            for log_line in log_stream:
                result += log_line
                self._tcpdump_output.append(log_line)
                LOG.debug('Result is: %s' % log_line.rstrip())
            LOG.debug('Result output is : %s' % result)
        except StopIteration:
            LOG.debug("Stream didn't block, command %s " % cmdline +
                      " timed out before pulling results.")

        return_code = self.compute_host.check_exit_status(exec_id)
        if return_code != 0:
            LOG.debug('%s return_code = %s != 0, no packets received... %r' % (
                cmdline,
                return_code,
                result
            ))
            return False

        LOG.debug('%s return_code = %s output = %r' % (
            cmdline,
            return_code,
            result))
        return True

    # Inherited methods
    # FIXME: remove sync or look where it is used
    def do_execute(self, cmdline, timeout=None, should_succeed=True,
                   stream=False, on_netns=False):
        """
        Execute in the underlying host inside the net namespace
        :param cmdline:
        :param timeout:
        :return:
        """
        cmdline = '%s %s' % (
            ('timeout %d ' % timeout if timeout else '', cmdline))
        result = self.compute_host.exec_command(cmdline,
                                                detach=False,
                                                stream=stream)
        return result

    # FIXME: is this necessary after the topology setup/teardown refactor?
    def do_clear_arp(self):
        cmdline = 'ip neigh flush all'
        LOG.debug('VethNs: flushing arp cache: ' + cmdline)
        return self.do_execute(cmdline)

    def set_up(self):
        return self.do_execute("ip link set dev %s up" % self.get_ifname())

    def set_down(self):
        return self.do_execute("ip link set dev %s down" % self.get_ifname())

    def inject_packet_loss(self, wait_time=0):
        cmdline = "iptables -i %s -A INPUT -j DROP" % self.get_ifname()
        LOG.debug('[%s] Dropping packets coming from %s' \
                  % (self.compute_host.get_hostname(), self.get_ifname()))
        self.execute(cmdline, sync=True)
        time.sleep(wait_time)

    def eject_packet_loss(self, wait_time=0):
        cmdline = "iptables -i %s -D INPUT -j DROP" % self.get_ifname()
        LOG.debug('[%s] Receiving packets coming from %s' \
                  % (self.compute_host.get_hostname(), self.get_ifname()))
        self.execute(cmdline, sync=True)
        time.sleep(wait_time)

    def get_ifname(self):
        return self.ifname

    def get_binding_ifname(self):
        return self.ifname

    def get_last_tcpdump_output(self):
        if len(self._tcpdump_output) > 0:
            return self._tcpdump_output[-1]
        return ""

    def get_tcpdump_output(self):
        return self._tcpdump_output

    def send_arp_request(self, target_ipv4):
        cmdline = "mz %s -t arp 'request, targetip=%s'" % \
                  (self.get_ifname(), target_ipv4)
        LOG.debug("cmdline: %s" % cmdline)
        return self.execute(cmdline, sync=True)

    def send_arp_reply(self, src_mac, target_mac, src_ipv4, target_ipv4):
        cmdline = "mz %s -t arp 'reply, smac=%s, tmac=%s, sip=%s, tip=%s'" % (
            self.get_ifname(),
            src_mac,
            target_mac,
            src_ipv4,
            target_ipv4)
        return self.execute(cmdline, sync=True)

    def verify_bandwidth(self, target_iface, max_kbps, burst_kb,
                         wait_for_burst=True, xfer_seconds=10,
                         allowed_lag=0.2, allowed_lead=0.05, tcp_port=10000):
        """
        Verifies bandwidth for traffic to target_iface.

        Sends xfer_seconds * max_kbps + burst_kb bits of data to target_iface
        over TCP, and verifies that it takes approximately xfer_seconds to
        complete.

        Args:
              target_iface: Interface to receive file.

              max_kbps: Sustained bandwidth, in kilobits per second.

              burst_kb: Maximum burst in kilobits (i.e. bucket size).

              wait_for_burst: If true, waits burst_kb / max_kbps seconds for
                              token bucket to fill.

              xfer_seconds: Expected length of transfer in seconds. Together
                            with max_kbps and burst_kb, this determines the
                            amount of data to be sent.

              allowed_lag: Transfer must complete within
                           (1 + allowed_lag) * xfer_seconds seconds.

              allowed_lead: Transfer may not complete sooner than
                           (1 - allowed_lead) * xfer_seconds seconds.

              tcp_port: TCP port to use for data transfer.
        """

        if wait_for_burst:
            time.sleep(float(burst_kb) / max_kbps)

        data_size = (burst_kb + max_kbps * xfer_seconds) * 1000 / 8
        max_time = (1 + allowed_lag) * xfer_seconds + 1
        min_time = (1 - allowed_lead) * xfer_seconds

        tmp_file = '/tmp/test_qos_data.txt'
        nc_listen_cmd = 'sh -c "nc -l -p %d > %s"' % (tcp_port, tmp_file)
        timeout = int(ceil(max_time * 1.25))
        listen_ftr = target_iface.execute(nc_listen_cmd, timeout + 1)

        # Give listening process time to start before we start sending.
        time.sleep(1)

        LOG.debug("Sending %d bytes to %s:%d. Should take %s-%s seconds." %
                  (data_size, target_iface.ipv4_addr, tcp_port, min_time,
                   max_time))

        nc_send_cmd = 'sh -c "head -c %d /dev/zero | nc %s %d"' % (
            data_size, target_iface.ipv4_addr, tcp_port)
        start_time = time.time()
        send_ftr = self.execute(nc_send_cmd, timeout)

        """ Give the processes a bit more than max_time, so that we can give
            better error messages in case of a slight time overrun."""
        listen_result = self.compute_host.check_exit_status(
            listen_ftr.result(max_time * 1.2)[1]['Id'])
        send_result = self.compute_host.check_exit_status(
            send_ftr.result(min_time * 1.2)[1]['Id'])
        elapsed_time = time.time() - start_time

        assert_that(listen_result == 0,
                    "Listening nc process failed with error: %d" %
                    listen_result)
        assert_that(send_result == 0,
                    "Sending nc process failed with error: %d" % send_result)

        assert_that(elapsed_time >= min_time,
                    "Transfer completed in %f seconds, expected >= %f" %
                    (elapsed_time, min_time))
        assert_that(elapsed_time <= max_time,
                    "Transfer completed in %f seconds, expected <= %f" %
                    (elapsed_time, max_time))

        target_iface.execute('rm %s' % tmp_file)

    def send_ether(self, ether_frame_string, count=1, sync=False):

        """
        Sends ethernet frame by using mz command.

        Args:
            ether_frame_string: hex_string for ethernet frame without
                                 white spaces. See man mz.

            count: Send the packet count times (default: 1, infinite: 0).


        Returns:
            Exit code of mz command

        """
        cmdline = 'mz %s -c %s %s' % (self.get_ifname(),
                                                   count,
                                                   ether_frame_string)
        LOG.debug("sending ethernet frame(s) with mz:  %s" % cmdline)

        LOG.debug("cmdline: %s" % cmdline)
        return self.execute(cmdline, sync=sync)

    def send_packet(self, target_hw, target_ipv4, pkt_type, pkt_parms,
                    payload_size, delay, count, sync=False,
                    src_hw=None, src_ipv4=None):
        src_addrs = ''
        if src_hw:
            src_addrs += '-a %s ' % src_hw
        if src_ipv4:
            src_addrs += '-A %s ' % src_ipv4

        payload = '0'
        if payload_size:
            payload = ''
            for len in xrange(payload_size):
                payload += '0'

        # Remove from headers hex_payload_file
        cmdline = "mz %s %s -b %s -B %s -t %s \"%s\" -P \"%s\" -d %ss -c %s" % (
            self.get_ifname(),
            src_addrs,
            target_hw,
            target_ipv4,
            pkt_type,
            pkt_parms,
            payload_size,
            delay,
            count
        )
        LOG.debug("cmdline: %s" % cmdline)
        return self.execute(cmdline, sync=sync)

    def send_udp(self, target_hw, target_ipv4, iplen=None,
                 payload_size=1,
                 src_port=9, dst_port=9, extra_params=None, delay=1, count=1,
                 sync=False, src_hw=None, src_ipv4=None):
        """ Sends UDP packets to target mac addr / ip address.

        Sends UDP packets from this interface to the target HW mac and ip
        address. Beware that the target hardware mac address may be a mac
        address of the target interface if it is connected to the same bridge
        (belongs to the same segment), or the router's incoming port mac if
        the receiver is in a different segment.

        NOTE: Currently the underlying layer uses mz for sending udp packets. mz
        requires that at least ip packet length to be specified. Ip packet length
        is computed as  28 + pay load file size where
            - 20 bytes for UDP packet frame
            - 8 bytes for addresses

        Args:
            target_hw: The target HW for this UDP message. Either the receiving
                interface's mac address if it is in the same network segment, or
                the router's incoming port's mac address
            target_ipv4: An IP address of the receiver.
            hex_payload_file: A name of the file containing hexadecimal pay load.
            iplen: The UDP packet length (see NOTE above for how to compute the
                length). Passing None will omit the parameter.
            src_port: A UDP source port. Passing None will omit the parameter.
            dst_port: A UDP destination port. Passing None will omit the
                parameter.
            extra_params: Comma-separated extra UDP packet parameters.
            delay: A message-sending delay.
            count: A message count.
            sync: Whether this call blocks (synchronous call) or not.
        """
        return self.send_protocol('udp', target_hw, target_ipv4, iplen,
                 payload_size, src_port, dst_port, extra_params,
                 delay, count, sync, src_hw, src_ipv4)

    def send_tcp(self, target_hw, target_ipv4, iplen,
                 payload_size=1,
                 src_port=9, dst_port=9, extra_params=None, delay=1, count=1,
                 sync=False):

        return self.send_protocol('tcp', target_hw, target_ipv4, iplen,
                 payload_size, src_port, dst_port, extra_params,
                 delay, count, sync)

    def send_protocol(self, protocol_name, target_hw, target_ipv4, iplen,
                 payload_size=1,
                 src_port=9, dst_port=9, extra_params=None, delay=1, count=1,
                 sync=False, src_hw=None, src_ipv4=None):
        params = []
        if src_port: params.append('sp=%d' % src_port)
        if dst_port: params.append('dp=%d' % dst_port)
        if iplen: params.append('iplen=%d' % iplen)
        if extra_params: params.append(extra_params)
        protocol_params = ','.join(params)
        return self.send_packet(target_hw=target_hw,
                                target_ipv4=target_ipv4,
                                pkt_type=protocol_name,
                                pkt_parms=protocol_params,
                                payload_size=payload_size,
                                delay=delay, count=count, sync=sync,
                                src_hw = src_hw, src_ipv4 = src_ipv4)

    def ping4(self, target_iface, interval=0.5, count=1, sync=False,
              size=56, should_succeed=True, do_arp=False, data=None):
        return self.ping_ipv4_addr(target_iface.get_ip(),
                                   interval,
                                   count,
                                   sync,
                                   size,
                                   should_succeed,
                                   do_arp,
                                   data)

    def ping_ipv4_addr(self, ipv4_addr, interval=0.5, count=1, sync=False,
                       size=56, should_succeed=True, do_arp=False, data=None):
        """Ping an IPv4 address."""

        if do_arp:
            # MidoNet requires some time to learn a new MAC address
            # since it has to write to Zookeeper and get an answer
            # We are advancing here MAC learning by sending an ARP
            # request one second before sending the ping. MN-662.
            self.send_arp_request(ipv4_addr)
            time.sleep(1)

        if data == None:
            data = "ff"

        ping_cmd = 'ping -i %s -c %s -s %s -p %s %s' % (
            interval,
            count,
            size,
            data,
            ipv4_addr
        )
        return self.execute(ping_cmd, should_succeed=should_succeed, sync=sync)

    """
    Helper methods to get data from the interface
    """

    def update_interface_name(self, new_name):
        self.execute('ip link set dev %s down' % self.ifname, sync=True)
        self.execute('ip link set dev %s name %s' % (self.ifname, new_name), sync=True)
        self.ifname = new_name
        self.execute('ip link set dev %s up' % self.ifname, sync=True)

    def get_mtu(self, update=False):
        if not self.mtu or update:
            self.mtu = self.execute(
                "sh -c \"ip link ls dev %s | head -n1 | cut -d' ' -f5\"" %
                self.get_ifname(),
                sync=True)
            LOG.debug("Infered mtu = %s" % self.mtu)
        return int(self.mtu)

    # TODO this function may not exactly belong here, but to host
    def get_num_routes(self, update=False):
        if not self.num_routes or update:
            self.num_routes = self.execute('sh -c \"ip route | wc -l\"', sync=True)
            LOG.debug("Infered num_routes = %s" % self.num_routes)
        return int(self.num_routes)

    def get_cidr(self, update=False):
        if not self.cidr or update:
            self.cidr = self.execute(
                ("sh -c \"ip addr ls dev %s | grep inet | "
                 "sed -e 's/[ \\t]\+/\\t/g' -e 's/^\\t//' | cut -f2\"") %
                self.get_ifname(),
                sync=True)
            LOG.debug("Infered cidr = %s" % self.cidr)
        return self.cidr

    def get_ip(self, update=False):
        if not self.ipv4_addr or update:
            cidr = self.get_cidr(update)
            self.ipv4_addr = cidr.split('/')[0]
            LOG.debug("Infered ip = %s" % self.ipv4_addr)
        return self.ipv4_addr

    def get_default_gateway_ip(self):
        return self.execute(
            "sh -c \"ip r | grep 'default via' | cut -d' ' -f3\"",
            sync=True)

    def get_mac_addr(self, update=False):
        if not self.hw_addr or update:
            self.hw_addr = self.execute(
                "sh -c \"ip addr ls dev %s | grep link | cut -d' ' -f2\"" %
                self.get_ifname(),
                sync=True)
            LOG.debug("Infered mac_addr = %s" % self.hw_addr)
        return self.hw_addr

    def __repr__(self):
        return "[iface=%r, vport_id=%r, mac_addr=%r]" % (
            self.get_ifname(),
            self.vport_id,
            self.get_mac_addr())
