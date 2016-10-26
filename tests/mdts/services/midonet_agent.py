#
# Copyright 2015 Midokura SARL
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#    http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#
import random
from mdts.services.interface import Interface
from mdts.services.jmx_monitor import JMXMonitor

from mdts.services.service import Service
from mdts.services.vmguest import VMGuest
import uuid
import logging
from mdts.services import service
from mdts.lib.bindings import BindingType

LOG = logging.getLogger(__name__)

class MidonetAgentHost(Service):

    def __init__(self, container_id):
        super(MidonetAgentHost, self).__init__(container_id)
        self.compute_num = int(self.get_hostname().split('midolman')[1])
        self.api = None
        self.midonet_host_id = None
        self.num_interfaces = 0

    def get_api(self):
        if not self.api:
            self.api = service.get_container_by_hostname('cluster1').\
                get_midonet_api(timeout=120)
        return self.api

    def get_service_status(self):
        try:
            hosts = self.get_api().get_hosts()
            midonet_host_id = self.get_midonet_host_id()
            for h in hosts:
                if h.get_id() == midonet_host_id:
                    LOG.debug('Host %s found! is alive? %s' % (
                        midonet_host_id,
                        h.is_alive()
                    ))
                    return 'up' if h.is_alive() else 'down'
            LOG.error('Host %s not found.' % midonet_host_id)
            return 'down'
        except:
            return 'down'

    def get_service_name(self):
        """
        Return the name of the upstart service to be started/stopped
        :return: str Name of the service
        """
        return 'midolman'

    def get_jmx_monitor(self):
        LOG.debug("Creating JMX Monitor for agent")
        monitor = JMXMonitor()
        monitor.connect(self.get_ip_address(), 7201)
        return monitor

    def get_midonet_host_id(self):
        if not self.midonet_host_id:
            self.midonet_host_id = self.exec_command(
                "sh -c \"cat /etc/midonet_host_id.properties \
                | tail -n1 \
                | cut -d= -f2\"")
        return str(self.midonet_host_id)

    def get_service_logs(self):
        return ['/var/log/midolman/midolman.log',
                '/var/log/midolman/minions.log',
                '/var/log/midolman/upstart-stderr.log',
                '/var/log/midolman/minions-stderr.log']

    def get_debug_logs(self):
        # Dump jstack
        jpids = self.exec_command('pgrep java').split()
        debug_logs = "Dumping jstacks...\n"
        for jpid in jpids:
            debug_logs += "JSTACK pid=%s\n" % jpid
            debug_logs += self.exec_command('jstack -F %s' % jpid)
            debug_logs += '\n'
        debug_logs += "-----------------------------------\n"
        debug_logs += "Midolman gc logs\n"
        debug_logs += "-----------------------------------\n"
        debug_logs += self.exec_command(
            'tail -n +1 /var/log/midolman/gc-*.log*')
        debug_logs += "\n"
        debug_logs += "-----------------------------------\n"
        debug_logs += "Minions gc logs\n"
        debug_logs += "-----------------------------------\n"
        debug_logs += self.exec_command(
            'tail -n +1 /var/log/midolman/minions/gc-*.log*')
        return debug_logs

    def is_haproxy_running(self, pool_id):
        result = self.exec_command("sh -c \"pgrep -a haproxy | grep %s\"" %
                                   pool_id)

        return result != ""

    def hm_namespace_exists(self, pool_id):
        result = self.exec_command("sh -c \"ip netns | grep %s_hm\"" %
                pool_id[:8])
        return result != ""

    def hm_resources_exist(self, pool_id):
        # Pre: assume pool_id is a valid UUID
        return self.is_haproxy_running(pool_id) and self.hm_namespace_exists(pool_id)

    def vppctl(self, cmd):
        self.try_command_blocking("vppctl %s" % cmd)

    def create_vmguest(self, **iface_kwargs):
        """

        :param vm_id:
        :return:
        """
        self.num_interfaces += 1
        vm_id = str(uuid.uuid4())[:8]
        # TODO: Create namespaces and interfaces in the host. Create a VMGuest
        # object which handles the interface communication
        if 'ifname' not in iface_kwargs or iface_kwargs['ifname'] is None:
            vm_id = str(uuid.uuid4())[:8]
        else:
            vm_id = iface_kwargs['ifname']

        self.exec_command(
            'ip link add dev veth%s type veth peer name peth%s' % (
                vm_id, vm_id))

        # Disable ipv6 for the namespace

        self.exec_command('ip netns add vm%s' % vm_id)
        self.exec_command('ip netns exec vm%s sysctl -w net.ipv6.conf.default.disable_ipv6=1' % vm_id)
        self.exec_command('ip netns exec vm%s sysctl -w net.ipv6.conf.all.disable_ipv6=1' % vm_id)
        # MAC Address of hosts
        # aa:bb:cc:RR:HH:II where:
        # aa:bb:cc -> constant
        # RR -> random number to avoid collisions when reusing interface ids
        # HH -> id of the host
        # II -> id of the interface inside the host (increasing monotonic)
        if 'hw_addr' not in iface_kwargs:
            iface_kwargs['hw_addr'] = 'aa:bb:cc:%0.2X:%0.2X:%0.2X' % (
                random.randint(0, 255),
                self.compute_num % 255,
                self.num_interfaces % 255
            )

        # FIXME: define veth, peth and vm names consistently and in one place
        self.exec_command(
            'ip link set address %s dev peth%s' % (
                iface_kwargs['hw_addr'],
                vm_id))
        # set veth up
        self.exec_command('ip link set veth%s up' % vm_id)
        self.exec_command('ip link set dev peth%s up netns vm%s' %
                          (vm_id, vm_id))

        # FIXME: move it to guest?
        # FIXME: hack for the yaml physical topology definition, fix it
        if 'ipv4_addr' in iface_kwargs and len(iface_kwargs['ipv4_addr']) > 0:
            self.exec_command(
                'ip netns exec vm%s ip addr add %s dev peth%s' % (
                    vm_id,
                    iface_kwargs['ipv4_addr'][0],
                    vm_id
                )
            )

        if 'ipv4_gw' in iface_kwargs:
            self.exec_command(
                'ip netns exec vm%s ip route add default via %s' % (
                    vm_id,
                    iface_kwargs['ipv4_gw']
                )
            )

        if 'mtu' in iface_kwargs:
            self.exec_command(
                'ip netns exec vm%s ip link set mtu %s dev peth%s' % (
                    vm_id,
                    iface_kwargs['mtu'],
                    vm_id
                )
            )

        iface_kwargs['compute_host'] = self

        return VMGuest(vm_id, **iface_kwargs)

    def destroy_vmguest(self, vm_guest):
        self.exec_command('ip netns exec vm%s ip link set dev peth%s down' % (
            vm_guest.get_vm_id(),
            vm_guest.get_vm_id()
        ))
        self.exec_command('ip netns del vm%s' % vm_guest.get_vm_id())

    def create_trunk(self, **iface_kwargs):
        raise NotImplementedError()

    def destroy_trunk(self, **iface_kwargs):
        raise NotImplementedError()

    def create_provided(self, **iface_kwargs):
        iface_kwargs['compute_host'] = self
        return Interface(**iface_kwargs)

    def destroy_provided(self, iface):
        pass

    def bind_port(self, interface, mn_port_id, type=BindingType.API):
        if type == BindingType.API:
            host_ifname = interface.get_binding_ifname()
            host_id = self.get_midonet_host_id()
            self.get_api().get_host(host_id) \
                .add_host_interface_port() \
                .port_id(mn_port_id) \
                .interface_name(host_ifname).create()
        elif type == BindingType.MMCTL:
            self.exec_command("mm-ctl --bind-port {} {}"
                              .format(mn_port_id, interface.get_binding_ifname()))

    def unbind_port(self, interface, type=BindingType.API):
        port = None
        compute_host_id = interface.compute_host.get_midonet_host_id()
        for p in self.get_api().get_host(compute_host_id).get_ports():
            if p.get_interface_name() == interface.get_binding_ifname():
                port = p

        if port is not None:
            # If it's none, the binding was already removed during the test.
            # Ignoring.
            if type == BindingType.API:
                port.delete()
            elif type == BindingType.MMCTL:
                self.exec_command("mm-ctl --unbind-port {}"
                                  .format(port.get_port_id()))
