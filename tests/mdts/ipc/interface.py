from mdts.host.interface import InterfaceProxy

from concurrent.futures import ThreadPoolExecutor
import pdb
import logging
import subprocess

LOG = logging.getLogger(__name__)
NUM_WORKERS = 10
EXECUTOR = ThreadPoolExecutor(max_workers=NUM_WORKERS)

def get_provider():
    return LocalInterfaceProvider


class RemoteInterfaceProvider:
    pass


class LocalInterfaceProvider:
    """Accesses directly to the InterfaceProxy by bypassing
    message passing. Just for local testing use.
    """

    def __init__(self, interface, host):
        self._proxy = InterfaceProxy(interface, host)
        self._interface = interface
        self._host = host


    def handle_sync(funk):
        def wrapped(self, **kwargs):
            future = funk(self, **kwargs)
            if kwargs.get('sync'):
                return future.result()
            else:
                return future
        return wrapped

    @handle_sync
    def create(self, sync=False):

        future = EXECUTOR.submit(self._proxy.create)

        # if Midolman is running inside a namespace
        if self._host['mm_namespace'] and self._interface['type'] != "provided":
            nsname = self._host['mm_namespace']

            def put_veth_to_mm_ns(future):
                self._interface = future.result()
                ifname = self._interface['ifname']

                cmdline = 'ip link set %s netns %s' % (ifname, nsname)
                LOG.debug('VethNs: putting host veth to MM ns ' + cmdline)
                subprocess.check_call(cmdline.split())

                cmdline = 'ip netns exec %s ip  link set %s up' % (nsname,
                                                                   ifname)
                LOG.debug('VethNs: setting IF up ' + cmdline)
                subprocess.check_call(cmdline.split())
            future.add_done_callback(put_veth_to_mm_ns)
        return future

    @handle_sync
    def delete(self, sync=False):
        return  EXECUTOR.submit(self._proxy.delete)

    @handle_sync
    def execute(self, cmdline=None, timeout=None, sync=False):
        return  EXECUTOR.submit(self._proxy.execute, cmdline, timeout)

    def execute_interactive(self, cmdline):
        return self._proxy.exec_interactive(cmdline)

    def exec_interactive(self, cmdline):
        return self._proxy.exec_interactive(cmdline)

    @handle_sync
    def expect(self, pcap_filter_string, timeout, sync=False):
        return  EXECUTOR.submit(self._proxy.expect, pcap_filter_string,
                                timeout)

    @handle_sync
    def send_arp_request(self, target_ipv4, sync=False):
        return EXECUTOR.submit(self._proxy.send_arp_request, target_ipv4)

    @handle_sync
    def send_arp_reply(self, src_mac, target_mac, src_ipv4, target_ipv4):
        return EXECUTOR.submit(self._proxy.send_arp_reply,
                               src_mac, target_mac, src_ipv4, target_ipv4)

    @handle_sync
    def clear_arp(self, sync=False):
        return  EXECUTOR.submit(self._proxy.clear_arp)

    @handle_sync
    def set_up(self, sync=False):
        return EXECUTOR.submit(self._proxy.set_up)

    @handle_sync
    def set_down(self, sync=False):
        return EXECUTOR.submit(self._proxy.set_down)
