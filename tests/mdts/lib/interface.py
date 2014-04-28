import mdts.ipc.interface
from mdts.lib.util import ping4_cmd

import time
import logging
import re
import os

PROVIDER = mdts.ipc.interface.get_provider()
LOG = logging.getLogger(__name__)

class Interface:

    def __init__(self, interface, host):
        self.interface = interface
        self.host = host
        self.vport_id = None
        if not self.interface.get('ifname'):
            self.interface['ifname'] = self.get_ifname()

        #: :type: LocalInterfaceProvider
        self._delegate = PROVIDER(self.interface, host)

    def get_ifname(self):
        interface_id_string = '%03d' % self.interface['id']
        host_id_string = '%04d' % self.host['id']
        return 'if-' + host_id_string + '-' + interface_id_string

    def create(self, sync=False):
        return self._delegate.create(sync=sync)

    def delete(self, sync=False):
        return self._delegate.delete(sync=sync)

    def execute(self, cmdline, timeout=None, sync=False):
        return self._delegate.execute(cmdline=cmdline, timeout=timeout,
                                      sync=sync)

    def execute_interactive(self, cmdline):
        return self._delegate.execute_interactive(cmdline)

    def exec_interactive(self, cmdline):
        """
        @type cmdline str

        :rtype:
        """
        self._delegate.exec_interactive(cmdline)

    def expect(self, pcap_filter_string, timeout=3, sync=False):
        return self._delegate.expect(pcap_filter_string=pcap_filter_string,
                                     timeout=timeout, sync=sync)

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
        cmdline = 'mz $peer_if -c %s %s 2> /dev/null' % (count,
                                                         ether_frame_string)
        LOG.debug("sending ethernet frame(s) with mz:  %s" % cmdline)

        LOG.debug("cmdline: %s" % cmdline)
        return self.execute(cmdline, sync=sync)

    def send_packet(self, target_hw, target_ipv4, pkt_type, pkt_parms,
                    hex_payload_file, delay, count, sync=False):
        fmt = 'mz $peer_if -b {0} -B {1} -t {2} "{3}" -F {4} -d {5}s -c {6}'
        fmt += ' 2> /dev/null'
        cmdline = fmt.format(target_hw, target_ipv4,
                             pkt_type, pkt_parms,
                             hex_payload_file, count, delay)
        LOG.debug("cmdline: %s" % cmdline)
        return self.execute(cmdline, sync=sync)

    def send_arp_request(self, target_ipv4, sync=False):
        return self._delegate.send_arp_request(target_ipv4=target_ipv4, sync=sync)

    def send_arp_reply(self, src_mac, target_mac, src_ipv4, target_ipv4):
        return self._delegate.send_arp_reply(
            src_mac=src_mac, target_mac=target_mac,
            src_ipv4=src_ipv4, target_ipv4=target_ipv4)

    def send_udp(self, target_hw, target_ipv4, iplen,
                 hex_payload_file='trivial-test-udp',
                 src_port=9, dst_port=9, extra_params=None, delay=3, count=1,
                 sync=False):
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
                 hex_payload_file, src_port, dst_port, extra_params,
                 delay, count, sync)

    def send_tcp(self, target_hw, target_ipv4, iplen,
                 hex_payload_file='trivial-test-udp',
                 src_port=9, dst_port=9, extra_params=None, delay=3, count=1,
                 sync=False):

        return self.send_protocol('tcp', target_hw, target_ipv4, iplen,
                 hex_payload_file, src_port, dst_port, extra_params,
                 delay, count, sync)

    def send_protocol(self, protocol_name, target_hw, target_ipv4, iplen,
                 hex_payload_file='trivial-test-udp',
                 src_port=9, dst_port=9, extra_params=None, delay=3, count=1,
                 sync=False):
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
                                hex_payload_file=hex_payload_file,
                                delay=delay, count=count, sync=sync)

    def make_web_request_get_backend(self, dst_ip_port):
        """
        @type dst_ip str
        @type dst_port int

        Make a HTTP GET (TCP connection) to dst_ip on port dst_port.

        Returns: (IP address of backend we hit <string>, port of backend we hit <int>)
        """
        timeout_secs = 5
        res = self.make_web_request_to(dst_ip_port, timeout_secs).result().split(":")
        ip_addr = res[0]
        port = int(res[1])
        return ip_addr, port

    def make_web_request_to(self, dst_ip_port, timeout_secs = 5):
        """
        @type dst_ip_port (str, int)

        Make a HTTP GET (TCP connection) to dst_ip on port dst_port.

        Returns: A future
        """
        dst_ip, dst_port = dst_ip_port
        return self.execute("curl -s -m %s http://%s:%s" % (timeout_secs, dst_ip, dst_port))

    def start_web_server(self, port):
        """
        @type port int

        Listens for a TCP connection on the specified port. Returns a
        simple 200 OK with the listening namespace's ip address / port if it receives a GET.
        """
        this_file_dir = os.path.dirname(os.path.realpath(__file__))
        web_server_location = os.path.join(this_file_dir, "../tests/utils/nsinfo_web_server.py")
        web_start_command = "python %s %s" % (web_server_location, port)
        return self.execute_interactive(web_start_command)

    def clear_arp(self, sync=False):
        return self._delegate.clear_arp(sync=sync)

    def ping4(self, target_iface, interval=0.5, count=3, sync=False,
              size=56, suppress_failure=False, do_arp=False):
        """Ping to the first IPv4 address  given by target_iface"""
        ipv4_addrs = target_iface.interface.get('ipv4_addr')
        assert isinstance(ipv4_addrs, list)
        assert len(ipv4_addrs) > 0
        ipv4_addr, _ = ipv4_addrs[0].split('/')

        return self.ping_ipv4_addr(ipv4_addr, interval, count, sync, size,
                                   suppress_failure, do_arp)

    def ping_ipv4_addr(self, ipv4_addr, interval=0.5, count=3, sync=False,
                       size=56, suppress_failure=False, do_arp=False):
        """Ping an IPv4 address."""

        if do_arp:
            # MidoNet requires some time to learn a new MAC address
            # since it has to write to Zookeeper and get an answer
            # We are advancing here MAC learning by sending an ARP
            # request one second before sending the ping. MN-662.
            self.send_arp_request(ipv4_addr)
            time.sleep(1)

        ping_cmd = ping4_cmd(ipv4_addr, interval, count, size)
        # Suppress an exception thrown by the future when the ping command
        # doesn't get any replies so that the execution terminates normally.
        if suppress_failure:
            ping_cmd += " || true"
        return self.execute(ping_cmd, sync=sync)

    def get_mtu(self):
        mtu = 0
        mtu_output = self.execute('ip link ls dev $peer_if').result().split()
        for index in range(len(mtu_output)):
            if mtu_output[index] == "mtu":
                mtu = mtu_output[index + 1]

        return mtu

    # TODO this function may not exactly belong here, but to host
    def get_num_routes(self):
        route_output = self.execute('ip route').result()
        if route_output:
            return len(route_output.split('\n'))

        return 0

    def get_cidr(self):
        ip_output = self.execute('ip addr ls dev $peer_if').result()
        cidr = None
        for l in ip_output.split('\n'):
            match = re.match(' *inet (.*) brd.*', l)
            if match:
                cidr = match.group(1)

        return cidr

    def get_ip(self):
        ip_output = self.execute('ip addr ls dev $peer_if').result()
        for l in ip_output.split('\n'):
            match = re.match(' *inet ([0-9.]+)/[0-9]+', l)
            if match:
                return match.group(1)

        return None

    def get_mac_addr(self):
        ip_output = self.execute('ip link ls dev $peer_if').result().split()
        for i in range(len(ip_output)):
            if ip_output[i] == "link/ether":
                return ip_output[i + 1]
        return None

    def set_up(self):
        self._delegate.set_up()

    def set_down(self):
        self._delegate.set_down()

    def __repr__(self):
        return "[Interface=%r, vport_id=%r]" % (self.interface,
                                                self.vport_id)
