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

from collections import namedtuple

import logging
import os
import pdb
import re
import shlex
import string
import subprocess

from threading import Semaphore

from mdts.lib import subprocess_compat

LOG = logging.getLogger(__name__)

#http://blip.tv/pyohio/data-transfer-objects-are-a-disease-meet-the-cure-5437099
# Not sure if this is a good idea yet, though

#INTERFACE = namedtuple("INTERFACE",
#                 "id ifname type ipv4_addr ipv6_addr hw_addr mtu")

EXPECT_FRAME_COUNT=1


class InterfaceProxy:

    def __init__(self, interface, host):
        # pack it to INTERFACE DTO using namedtuple
        #self._interface = INTERFACE(**interface)
        self._interface = interface
        self._host = host
        self._concrete = None

        if self._interface['type'] == 'netns':
           self._concrete = Netns(self._interface)
        elif self._interface['type']  == 'tap':
            self._concrete = Tap(self._interface)
        elif self._interface['type'] == 'trunk':
            self._concrete = Trunk(self._interface, self._host)
        elif self._interface['type'] == 'provided':
            self._concrete = Provided(self._interface, self._host)
        else:
            raise NotImplementedError('%s not supported' % self._interface)

    def create(self):
        assert self._concrete
        return self._concrete.create()

    def delete(self):
        assert self._concrete
        return self._concrete.delete()

    def execute(self, cmdline, timeout):
        assert self._concrete
        return self._concrete.execute(cmdline, timeout)

    def exec_interactive(self, cmdline):
        assert self._concrete
        return self._concrete.exec_interactive(cmdline)

    def expect(self, pcap_filter_string, timeout):
        assert self._concrete
        return self._concrete.expect(pcap_filter_string, timeout)

    def send_arp_request(self, target_ipv4):
        assert self._concrete
        return self._concrete.send_arp_request(target_ipv4)

    def send_arp_reply(self, src_mac, target_mac, src_ipv4, target_ipv4):
        assert self._concrete
        return self._concrete.send_arp_reply(
            src_mac, target_mac, src_ipv4, target_ipv4)

    def clear_arp(self):
        assert self._concrete
        return self._concrete.clear_arp()

    def set_up(self):
        assert self._concrete
        return self._concrete.set_up()

    def set_down(self):
        assert self._concrete
        return self._concrete.set_down()


class Tap:
    pass


class Netns(object):

    def __init__(self, interface):
        LOG.debug('interface=%s', interface)
        self._interface = interface
        self._tcpdump_sem = Semaphore(value=0)

    def _get_ifname(self):
        return self._interface['ifname']

    def _get_peer_ifname(self):
        if self._interface.get('peer_ifname'):
            return self._interface.get('peer_ifname')
        return "p" + self._get_ifname()[1:]

    def _get_nsname(self):
        return "ns-" + self._get_ifname()

    def create(self):
        """Create a veth + network namespace.

        Args:
            N/A

        Returns:
           None

        Raises:
            subprocess.CalledProcessError: when one of the commands to set
                                           up the veth+ns environment fails
        """

        LOG.debug('create')
        ifname = self._get_ifname()
        ifname_peer = self._get_peer_ifname()

        #NOTE: may want to have idempotent version?
        try:
            # create a veth pair
            cmdline =  "ip link add dev %s type veth peer name %s" % (
                ifname, ifname_peer)
            LOG.debug('VethNs: creating a veth pair: ' + cmdline)
            subprocess_compat.check_output(cmdline.split())

            # create a network namespace
            cmdline =  "ip netns add " + self._get_nsname()
            LOG.debug('VethNs: creating a network namespace: ' + cmdline)
            subprocess_compat.check_output(cmdline.split())

            if not self._interface.get('ipv6_addr'):
                # disable ipv6 if there's no address configured
                cmdline = 'sysctl -w net.ipv6.conf.default.disable_ipv6=1'
                self.execute(cmdline)

           # configure hw address if any
            if self._interface.get('hw_addr'):
                cmdline = 'ip link set address %s dev %s' % (
                    self._interface['hw_addr'],
                    ifname_peer)
                LOG.debug('VethNs: setting HW address: ' + cmdline)
                subprocess_compat.check_output(cmdline.split())

            # put the veth peer IF to the namespace that's just created.
            cmdline = "ip link set dev %s up netns %s" % (ifname_peer,
                                                          self._get_nsname())
            LOG.debug('VethNs: putting a veth to a ns: ' + cmdline)
            subprocess_compat.check_output(cmdline.split())

            # configure ipv4 address if any
            for addr in self._interface.get('ipv4_addr'):
                cmdline = 'ip addr add %s dev %s' % (addr, ifname_peer)
                LOG.debug('VethNs: adding ip address in the ns: ' + cmdline)
                self.execute(cmdline)

            # add default route if gateway is configured
            if self._interface.get('ipv4_gw'):
                cmdline = 'ip netns exec %s ip route add default via %s' %\
                          (self._get_nsname(), self._interface['ipv4_gw'])
                LOG.debug('VethNs: adding default route:' + cmdline)
                self.execute(cmdline)

            # takes care of ipv6 address
            if self._interface.get('ipv6_addr'):
                #TODO
                pass
            else:
                cmdline = 'ip -6 addr flush dev %s' % ifname_peer
                LOG.debug('VethNs: flushing ipv6 address: ' + cmdline)
                self.execute(cmdline)

             # set MTU if any
            if self._interface.get('mtu'):
                cmdline = "ip link set mtu %s dev %s" % (
                    self._interface['mtu'],
                    ifname_peer)
                LOG.debug('VethNs: configuring mtu in the ns: ' + cmdline)
                self.execute(cmdline)

            return self._interface

        except Exception as e:
            LOG.error('Either creating veth or namespace failed: ' + str(e))
            raise e

    def get(self, interface):
        """TBD"""
        raise NotImplementedError

    def update(self, interface):
        raise NotImplementedError

    def delete(self):

        """
        Delete this veth + NS
        """

        cmdline = "ip netns del " + self._get_nsname()
        LOG.debug('VethNs: deleting the ns: ' + cmdline)
        try:
            subprocess_compat.check_output(cmdline.split())
        except subprocess.CalledProcessError as e:
            LOG.exception(e)
        except OSError as e:
            LOG.exception(e)

    def _process_cmdline(self, cmdline, timeout=None):
        """
        @type cmdline str
        @type timeout int
        :rtype: str
        """
        cmdline = "ip netns exec %s %s" % (
            self._get_nsname(),
            ('timeout %d ' % timeout if timeout else "")  + cmdline)
        if string.find(cmdline, '$peer_if') >= 0:
            cmdline = re.sub('\$peer_if', self._get_peer_ifname(), cmdline)
        if string.find(cmdline, '$if') >= 0:
            cmdline = re.sub('\$if', self._get_ifname(), cmdline)
        return cmdline

    def execute(self, cmdline, timeout=None):
        """Executes cmdline inside the namespace

        Args:
            cmdline: command line string that'll be executed in the network
                      namespace.
            timeout: timeout in second

        Returns:
            output as a bytestring


        Raises:
            subprocess.CalledProcessError: when the command exists with non-zero
                                           value, including timeout.
            OSError: when the executable is not found or some other error
                     invoking
        """

        cmdline = self._process_cmdline(cmdline, timeout)
        LOG.debug('VethNs: executing command: %s', cmdline)
        #NOTE(tomoe): I know shell=True is insecure, but I trust people here :)
        result = subprocess_compat.check_output(cmdline, shell=True)
        LOG.debug('Result=%r', result)
        return result

    def execute_suppress_output(self, cmdline):
        """Executes cmdline inside the namespace, but suppresses output

        Args:
            cmdline: command line string that'll be executed in the network
                      namespace.

        Returns:
            return code of the called process

        Raises:
            OSError: when the executable is not found or some other error
                     invoking
        """
        cmd = ['ip', 'netns', 'exec', self._get_nsname()] + cmdline
        with open(os.devnull, "w") as fnull:
            LOG.debug('execute ' + ' '.join(cmd))
            try:
                return subprocess.call(cmd, stdout=fnull, stderr=fnull)
            except OSError as e:
                LOG.exception(e)
                return -1

    def exec_interactive(self, cmdline):
        """Executes cmdline inside the namespace interactively, i.e.
        returning the process object.

        @type cmdline str
        Command to execute in the network namespace

        :rtype: Popen
        Process object. Contains I/O streams for interaction.
        """
        cmdline = self._process_cmdline(cmdline)
        cmd_args = shlex.split(cmdline)

        LOG.debug('VethNs: Executing command interactively: %s', cmdline)
        return subprocess.Popen(cmd_args, shell=False, stdin=subprocess.PIPE,
                                stdout=subprocess.PIPE, stderr=subprocess.PIPE)

    def expect(self, pcap_filter_string, timeout):
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

        base_cmdline = 'timeout %s tcpdump -n -l -i %s -c %s %s 2>&1' % (
            timeout,
            self._get_peer_ifname(),
            EXPECT_FRAME_COUNT, pcap_filter_string)

        try:
            try:
                cmdline = self._process_cmdline(base_cmdline)
                LOG.debug('running tcp dump=%s', cmdline)
                p = subprocess.Popen(cmdline, stdout=subprocess.PIPE,
                                     stderr=subprocess.PIPE, shell=True)

                # We are running tcpdump, set the flag to continue main thread
                LOG.debug('tcp dump running OK')
            finally:
                self._tcpdump_sem.release()

            stdout, stderr = p.communicate()
            retcode = p.poll()

            LOG.debug('output=%r', stdout)

            # Retcode will return non-zero if the timeout passed and no packet came
            if retcode:
                raise subprocess.CalledProcessError(retcode, str(("tcpdump", stdout, stderr)))

            retval = True
        except subprocess.CalledProcessError as e:
            LOG.debug('expect failed=%s', e)
            retval = False
        LOG.debug('Returning %r', retval)
        return retval

    def send_arp_request(self, target_ipv4):
        cmdline = 'mz %s -t arp "request, targetip=%s" 2> /dev/null' %\
            (self._get_peer_ifname(), target_ipv4)
        LOG.debug("cmdline: %s" % cmdline)
        return self.execute(cmdline)

    def send_arp_reply(self, src_mac, target_mac, src_ipv4, target_ipv4):
        arp_msg = '"' + "reply, smac=%s, tmac=%s, sip=%s, tip=%s" %\
                  (src_mac, target_mac, src_ipv4, target_ipv4) + '"'
        mz_cmd = ['mz', self._get_peer_ifname(), '-t', 'arp', arp_msg]
        return self.execute_suppress_output(mz_cmd)

    def clear_arp(self):
        cmdline = 'ip neigh flush all'
        LOG.debug('VethNs: flushing arp cache: ' + cmdline)
        self.execute(cmdline)

    def set_up(self):
        return self.execute("ip link set dev $peer_if up")

    def set_down(self):
        return self.execute("ip link set dev $peer_if down")


class Trunk(Netns):

    def __init__(self, interface, host):
        super(Trunk, self).__init__(interface)
        self._host = host

    def _get_ifname(self):
        return self._interface['ifname']

    def _get_peer_ifname(self):
        if self._interface.get('peer_ifname'):
            return self._interface.get('peer_ifname')
        return self._get_ifname() + "-br"

    def _get_nsname(self):
        return self._host['mm_namespace']

    def create(self):
        LOG.debug('create')
        brname = self._interface['bridge']['brname']
        ifname = self._get_ifname()
        ifname_peer = self._get_peer_ifname()

        #NOTE: may want to have idempotent version?
        try :
            # create a veth pair
            cmdline =  "ip link add dev %s type veth peer name %s" % (
                ifname, ifname_peer)
            LOG.debug('VethNs: creating a veth pair: ' + cmdline)
            subprocess_compat.check_output(cmdline.split())

            # add an end of the pair to bridge
            ifname_peer = self._get_peer_ifname()
            LOG.debug('Trunk: adding %s to %s' % (brname, ifname_peer))
            cmdline = "brctl addif %s %s" % (brname, ifname_peer)
            subprocess_compat.check_output(cmdline.split())
            cmdline = "ip link set dev %s up" % (ifname_peer,)
            subprocess_compat.check_output(cmdline.split())

            return self._interface

        except Exception as e:
            LOG.error('Either creating veth or namespace failed: ' + str(e))
            raise e

    def delete(self):
        """
        Delete the interface from bridge
        """
        LOG.debug("delete")

        brname = self._interface['bridge']['brname']
        ifname_peer = self._get_peer_ifname()
        cmdline = "brctl delif %s %s" % (brname, ifname_peer)
        LOG.debug('Trunk: deleting %s from %s' % (brname, ifname_peer))
        try:
            subprocess_compat.check_output(cmdline.split())
        except subprocess.CalledProcessError:
            pass
        except OSError:
            pass

        cmdline = "ip link delete " + ifname_peer
        LOG.debug('Trunk: deleting the iface: ' + ifname_peer)
        try:
            subprocess_compat.check_output(cmdline.split())
        except subprocess.CalledProcessError:
            pass
        except OSError:
            pass

    def set_up(self):
        iface = self._get_ifname()
        cmdline = 'ip link set dev %s up' % iface
        LOG.debug("cmdline: %s" % cmdline)
        return self.execute(cmdline)

    def set_down(self):
        iface = self._get_ifname()
        cmdline = 'ip link set dev %s down' % iface
        LOG.debug("cmdline: %s" % cmdline)
        return self.execute(cmdline)


class Provided(Netns):

    def __init__(self, interface, host):
        super(Provided, self).__init__(interface)
        self._host = host

    def _get_ifname(self):
        return self._interface['ifname']

    def _get_peer_ifname(self):
        return self._interface['ifname']

    def _get_nsname(self):
        return self._host['mm_namespace']

    def create(self):
        return self._interface

    def delete(self):
        pass
