# Copyright 2016 Midokura SARL
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


from mdts.lib.vtm_neutron import NeutronTopologyManager
from mdts.lib.bindings import BindingManager
from mdts.services import service
from mdts.tests.utils.utils import bindings

from nose.plugins.attrib import attr
from mdts.tests.utils import *
from hamcrest import *
from nose.tools import nottest, with_setup

import time
import pdb, logging
import re
import uuid

LOG = logging.getLogger(__name__)

UPLINK_VETH_MAC = '2e:0e:2f:68:00:11'
DOWNLINK_VETH_MAC = '2e:0e:2f:68:00:22'
DOWNLINK_VETH_MAC_2 = '2e:0e:2f:68:00:33'
TCP_SERVER_PORT = 9999
TCP_CLIENT_PORT = 9998

class NeutronVPPTopologyManagerBase(NeutronTopologyManager):
    def cleanup_veth(self, container, name):
        cont = service.get_container_by_hostname(container)
        cont.exec_command('ip l delete dev %s' % name)

    def create_veth_pair(self, container, ep1name, ep2name,
                         ep1mac=None, ep2mac=None):
        cont = service.get_container_by_hostname(container)
        cmd = 'ip l add name %s %s type veth peer name %s %s' % (
            ep1name, 'address %s' % ep1mac if ep1mac else '',
            ep2name, 'address %s' % ep2mac if ep2mac else '')
        # cleaning up one end will clean up both
        self.addCleanup(self.cleanup_veth, container, ep1name)
        cont.try_command_blocking(cmd)
        cont.try_command_blocking('ip l set up dev %s' % (ep1name))
        cont.try_command_blocking('ip l set up dev %s' % (ep2name))

    def remove_port_from_ovs(self, container, portname):
        cont = service.get_container_by_hostname(container)
        cont.exec_command('mm-dpctl interface -d %s midonet' % portname)

    def add_port_to_ovs(self, container, portname):
        cont = service.get_container_by_hostname(container)
        cont.try_command_blocking('mm-dpctl interface -a %s midonet' % portname)
        self.addCleanup(self.remove_port_from_ovs, container, portname)

    def cleanup_ipv6_flows(self, container, from_port, to_port):
        cont = service.get_container_by_hostname(container)
        cont.exec_command(
            'mm-dpctl flows --delete -d midonet -e 86dd -i %s -o %s' %
            (from_port, to_port))
        cont.exec_command(
            'mm-dpctl flows --delete -d midonet -e 86dd -i %s -o %s' %
            (to_port, from_port))

    def create_ipv6_flows(self, container, from_port, to_port):
        cont = service.get_container_by_hostname(container)
        self.addCleanup(self.cleanup_ipv6_flows, container, from_port, to_port)
        cont.try_command_blocking(
            'mm-dpctl flows -d midonet -e 86dd -i %s -o %s' %
            (from_port, to_port))
        cont.try_command_blocking(
            'mm-dpctl flows -d midonet -e 86dd -i %s -o %s' %
            (to_port, from_port))

    def stop_vpp(self, container):
        cont = service.get_container_by_hostname(container)
        cont.try_command_blocking('service vpp stop')

    def start_vpp(self, container):
        cont = service.get_container_by_hostname(container)
        cont.try_command_blocking('service vpp restart')
        self.addCleanup(self.stop_vpp, container)

    def del_port_from_vpp(self, container, port):
        cont = service.get_container_by_hostname(container)
        try:
            cont.vppctl('delete host-interface name %s' % port)
        except:
            LOG.error("Erroring deleting interface %s from vpp" % port)

    def add_port_to_vpp(self, container, port, mac, address, vrf=0):
        cont = service.get_container_by_hostname(container)
        self.addCleanup(self.del_port_from_vpp, container, port)
        cont.vppctl('create host-interface name %s hw-addr %s' % (port, mac))
        cont.vppctl('set int state host-%s up' % port)
        if vrf != 0:
            cont.vppctl('set int ip table host-%s %d' % (port, vrf))
        cont.vppctl('set int ip address host-%s %s' % (port, address))

    def del_route_from_vpp(self, container, prefix, via, port):
        cont = service.get_container_by_hostname(container)
        try:
            cont.vppctl('ip route del %s' % (prefix))
        except:
            LOG.error("Erroring deleting route %s from vpp" % prefix)

    def add_route_to_vpp(self, container, prefix, via, port):
        cont = service.get_container_by_hostname(container)
        self.addCleanup(self.del_route_from_vpp, container, prefix, via, port)
        cont.vppctl('ip route add %s via %s host-%s' % (prefix, via, port))

    def setup_fip64(self, container, ip6fip, ip4fixed,
                    ip4PoolStart, ip4PoolEnd, tableId=0):
        cont = service.get_container_by_hostname(container)
        fip64DelCmd = "fip64 del %s" % ip6fip
        self.addCleanup(cont.vppctl, fip64DelCmd)
        cont.vppctl("fip64 add %s %s pool %s %s table %d" % (ip6fip,
                                                             ip4fixed,
                                                             ip4PoolStart,
                                                             ip4PoolEnd,
                                                             tableId))

    def cleanup_remote_host(self, container, interface, address):
        cont = service.get_container_by_hostname(container)
        cont.exec_command('ip r del 100.0.0.0/8')
        cont.exec_command('ip a del %s/64 dev %s' % (address, interface))
        cont.exec_command('ip -6 r del cccc:bbbb::/32')
        cont.exec_command('ip -6 r del cccc:cccc::/32')

        cont.exec_command('ip netns delete ip6')

    def setup_remote_host(self, container, interface, gw_address,
                          local_address, local_router):
        self.addCleanup(self.cleanup_remote_host, container, interface, gw_address)
        cont = service.get_container_by_hostname(container)

        cont.try_command_blocking('ip r add 100.0.0.0/8 via 10.1.0.1')
        cont.try_command_blocking('ip a add %s/64 dev %s' % (gw_address, interface))
        cont.try_command_blocking('ip -6 r add cccc:bbbb::/32 via 2001::1')
        cont.try_command_blocking('ip -6 r add cccc:cccc::/32 via 2001::1')

        # enable ip6 forwarding
        cont.try_command_blocking('sysctl net.ipv6.conf.all.forwarding=1')

        # setup ip6 namespace
        cont.try_command_blocking('ip netns add ip6')
        cont.try_command_blocking(
            'ip l add name ip6dp type veth peer name ip6ns')
        cont.try_command_blocking('ip l set netns ip6 dev ip6ns')
        cont.try_command_blocking('ip l set up dev ip6dp')
        cont.try_command_blocking('ip netns exec ip6 ip link set up dev lo')
        cont.try_command_blocking('ip netns exec ip6 ip link set up dev ip6ns')
        cont.try_command_blocking(
            'ip a add %s/48 dev ip6dp' % local_router)
        cont.try_command_blocking(
            'ip netns exec ip6 ip a add %s/48 dev ip6ns' % local_address)
        cont.try_command_blocking(
            'ip netns exec ip6 ip -6 r add default via %s' % local_router)

    def add_mn_router_port(self, name, router_id, port_address,
                           network_addr, network_len):
        port = self._midonet_api.get_router(router_id).add_port()
        port.port_address(port_address)\
            .network_address(network_addr)\
            .network_length(network_len).create()

        def delete_mn_port(portid):
            self._midonet_api.delete_port(portid)

        self.addCleanup(delete_mn_port, port.get_id())
        self.set_resource(name, port)
        return port

    def add_mn_route(self, router_id, src_prefix, dst_prefix,
                     via=None, port=None):
        pattern = re.compile("(.*)/(.*)")
        src = pattern.match(src_prefix)
        dst = pattern.match(dst_prefix)
        route = self._midonet_api.get_router(router_id).add_route()
        route.type("Normal")\
            .src_network_addr(src.group(1)).src_network_length(src.group(2))\
            .dst_network_addr(dst.group(1)).dst_network_length(dst.group(2))
        if via:
            route.next_hop_gateway(via)
        if port:
            route.next_hop_port(port)
        route.create()

        def delete_mn_route(routeid):
            self._midonet_api.delete_route(routeid)
        self.addCleanup(delete_mn_route, route.get_id())

    def get_mn_uplink_port_id(self, uplink_port):
        return str(uuid.UUID(int=uuid.UUID(uplink_port['id']).int ^
                             0x9c30300ec91f4f1988449d37e61b60f0L))

    def flush_neighbours(self, container, interface):
        cont_services = service.get_container_by_hostname(container)
        cont_services.try_command_blocking('ip neigh flush dev %s' % interface)

    def await_vpp_initialized(self, host_name,
                              vpp_uplink_name,
                              timeout):
        cmd = "vppctl show hardware"
        container = service.get_container_by_hostname(host_name)
        curr_moment = time.time()
        regexp = re.compile('up[\s\t]+host\-' + vpp_uplink_name)
        while timeout > 0:
            try:
                (statuc, output) = container.exec_command_and_get_output(cmd, 2)
                if regexp.search(output, re.MULTILINE):
                    return
            except RuntimeError as ex:
               pass
            timeout += curr_moment
            curr_moment = time.time()
            timeout -= curr_moment
        raise RuntimeError("Timed out waiting vpp to initialize")

class UplinkWithVPP(NeutronVPPTopologyManagerBase):

    def __init__(self):
        super(UplinkWithVPP, self).__init__()
        self.uplink_port = {}
        self.vrf = 0

    def build(self, binding_data=None):
        self._edgertr = self.create_router("edge")
        uplinknet = self.create_network("uplink", uplink=True)
        self.create_subnet("uplinksubnet6", uplinknet, "2001::/64",
                           enable_dhcp=False, version=6)

        self.uplink_port = self.create_port("uplinkport", uplinknet,
                                            host_id="midolman1",
                                            interface="bgp0",
                                            fixed_ips=["2001::1"])
        self.add_router_interface(self._edgertr, port=self.uplink_port)

        # setup quagga1
        self.setup_remote_host('quagga1', 'bgp1',
            gw_address = "2001::2",
            local_address = "bbbb::2",
            local_router = "bbbb::1")
        self.setup_remote_host('quagga2', 'bgp2',
            gw_address = "2001::3",
            local_address = "eeee::2",
            local_router = "eeee::1")

        self.flush_neighbours('quagga1', 'bgp1')
        self.flush_neighbours('quagga2', 'bgp2')
        self.flush_neighbours('midolman1', 'bgp0')

        uplink_port_id = self.get_mn_uplink_port_id(self.uplink_port)
        uplink_port_name = 'vpp-' + uplink_port_id[0:8]

        self.await_vpp_initialized('midolman1', uplink_port_name, 180)
        self.add_route_to_vpp('midolman1',
                              prefix='bbbb::/64',
                              via='2001::2',
                              port=uplink_port_name)
        self.add_route_to_vpp('midolman1',
                              prefix='eeee::/64',
                              via='2001::3',
                              port=uplink_port_name)

    def addTenant(self, name, pubnet, mac, port_name):

        self.vrf += 1

        dlink_vpp_name = name + '-dv'
        dlink_ovs_name = name + '-do'

        # build internal network
        tenantrtr = self.create_router(name)
        self.set_router_gateway(tenantrtr, pubnet)

        privnet = self.create_network("private-" + name)
        privsubnet = self.create_subnet("privatesubnet-" + name,
                                        privnet,
                                        '20.0.0.0/26')
        self.add_router_interface(tenantrtr, subnet=privsubnet)

        self.port1 = self.create_port(port_name, privnet)
        self.create_sg_rule(self.port1['security_groups'][0])

        # wire up downlink
        self.create_veth_pair('midolman1', dlink_vpp_name, dlink_ovs_name,
                              ep1mac=mac)
        self.add_port_to_vpp('midolman1',
                             port=dlink_vpp_name,
                             mac=mac,
                             address='10.0.0.1/24',
                             vrf=self.vrf)
        self.add_route_to_vpp('midolman1',
                              prefix="20.0.0.0/26",
                              via="10.0.0.2",
                              port=dlink_vpp_name)

        # hook up downlink to topology
        mn_tenant_downlink = self.add_mn_router_port(name,
                                                     tenantrtr['id'],
                                                     "10.0.0.2",
                                                     "10.0.0.0",
                                                     24)
        self.add_mn_route(tenantrtr['id'],
                          src_prefix = "0.0.0.0/0",
                          dst_prefix = "10.0.0.0/24",
                          port=mn_tenant_downlink.get_id())
        self.add_mn_route(tenantrtr['id'],
                          src_prefix = "0.0.0.0/0",
                          dst_prefix = "20.0.0.64/26",
                          via="10.0.0.1",
                          port=mn_tenant_downlink.get_id())
        return self.vrf

    def destroy(self):
        super(UplinkWithVPP, self).destroy()
        service.get_container_by_hostname('midolman1').\
                exec_command_blocking("restart midolman")

# Neutron topology with a single tenant and uplink
# configured
class SingleTenantAndUplinkWithVPP(UplinkWithVPP):
    def build(self, binding_data=None):
        super(SingleTenantAndUplinkWithVPP, self).build(binding_data)

        pubnet = self.create_network("public", external=True)
        pubsubnet = self.create_subnet("publicsubnet",
                                       pubnet,
                                       "100.0.0.0/8")

        # add to the edge router
        self.add_router_interface(self._edgertr, subnet=pubsubnet)

        global DOWNLINK_VETH_MAC

        vrf = self.addTenant('tenant',
                             pubnet,
                             DOWNLINK_VETH_MAC,
                             'port1')
        self.setup_fip64("midolman1",
                         ip6fip = 'cccc:bbbb::2',
                         ip4fixed = "20.0.0.2",
                         ip4PoolStart = "20.0.0.65",
                         ip4PoolEnd = "20.0.0.66",
                         tableId=vrf)

class SingleTenantWithNeutronIPv6FIP(UplinkWithVPP):

    def build(self, binding_data=None):
        super(SingleTenantWithNeutronIPv6FIP, self).build(binding_data)

        self.pubnet = self.create_network("public", external=True)

        pubsubnet6 = self.create_subnet("publicsubnet6",
                                        self.pubnet,
                                        "cccc:bbbb::/32",
                                        version=6)

        # add public net to the edge router
        self.add_router_interface(self._edgertr, subnet=pubsubnet6)

        #build tenant router
        tenant_name = 'tenant'
        tenantrtr = self.create_router(tenant_name)
        self.set_router_gateway(tenantrtr, self.pubnet)

        # build private network
        privnet = self.create_network("private-" + tenant_name)
        privsubnet = self.create_subnet("privatesubnet-" + tenant_name,
                                        privnet,
                                        '192.168.0.0/26')
        self.add_router_interface(tenantrtr, subnet=privsubnet)

        #IP 20.0.0.2 is auto assigned to port1
        self.port1 = self.create_port('port1', privnet)
        self.create_sg_rule(self.port1['security_groups'][0])

        self.create_resource(
            self.api.create_floatingip(
                {'floatingip':
                    {'floating_network_id': self.pubnet['id'],
                     'floating_ip_address': 'cccc:bbbb::3',
                     'port_id': self.port1['id'],
                     'tenant_id': 'admin'
                    }}
            )
        )

# Neutron topology with a 3 tenants and uplink
# configured
class MultiTenantAndUplinkWithVPP(UplinkWithVPP):
    def build(self, binding_data=None):
        super(MultiTenantAndUplinkWithVPP, self).build(binding_data)

        pubnet = self.create_network("public", external=True)
        pubsubnet = self.create_subnet("publicsubnet",
                                       pubnet,
                                       "100.0.0.0/8")

        # add to the edge router
        self.add_router_interface(self._edgertr, subnet=pubsubnet)

        global DOWNLINK_VETH_MAC
        global DOWNLINK_VETH_MAC_2

        vrf = self.addTenant('tenant1',
                             pubnet,
                             DOWNLINK_VETH_MAC,
                             'port1')
        self.setup_fip64("midolman1",
                         ip6fip = 'cccc:bbbb::2',
                         ip4fixed = "20.0.0.2",
                         ip4PoolStart = "20.0.0.65",
                         ip4PoolEnd = "20.0.0.67",
                         tableId = vrf)

        vrf = self.addTenant('tenant2',
                             pubnet,
                             DOWNLINK_VETH_MAC_2,
                             'port2')
        self.setup_fip64("midolman1",
                         ip6fip = 'cccc:cccc::2',
                         ip4fixed = "20.0.0.2",
                         ip4PoolStart = "20.0.0.65",
                         ip4PoolEnd = "20.0.0.67",
                         tableId = vrf)

binding_empty = {
    'description': 'nothing bound',
    'bindings': []
}

binding_multihost_singletenant = {
    'description': 'spanning across 2 midolman',
    'bindings': [
         {'vport': 'port1',
         'interface': {
             'definition': {"ipv4_gw": "20.0.0.1"},
             'hostname': 'midolman2',
             'type': 'vmguest'
         }},
         {'vport': 'tenant',
         'interface': {
             'definition': {'ifname': 'tenant-do'},
             'hostname': 'midolman1',
             'type': 'provided'
         }}
    ]
}

binding_multihost_singletenant_neutronfip6 = {
    'description': 'spanning across 2 midolman no tenant binding',
    'bindings': [
         {'vport': 'port1',
         'interface': {
             'definition': {"ipv4_gw": "192.168.0.1"},
             'hostname': 'midolman2',
             'type': 'vmguest'
         }}
    ]
}

binding_multihost_multitenant = {
    'description': 'spanning across 3 midolman',
    'bindings': [
         {'vport': 'port1',
         'interface': {
             'definition': {"ipv4_gw": "20.0.0.1"},
             'hostname': 'midolman2',
             'type': 'vmguest'
         }},
         {'vport': 'tenant1',
         'interface': {
             'definition': {'ifname': 'tenant1-do'},
             'hostname': 'midolman1',
             'type': 'provided'
         }},
         {'vport': 'port2',
         'interface': {
             'definition': {"ipv4_gw": "20.0.0.1"},
             'hostname': 'midolman3',
             'type': 'vmguest'
         }},
         {'vport': 'tenant2',
         'interface': {
             'definition': {'ifname': 'tenant2-do'},
             'hostname': 'midolman1',
             'type': 'provided'
         }}
    ]
}


# Runs ping6 command with given ip6 address from given containter
# Count provides the number of packets ping will send.
# The number must be positive
def ping_from_inet(container, ipv6 = '2001::1', count=4, namespace=None):
    count = max(1, count)
    cmd = "%s ping6 %s -c %d" % (
        "ip netns exec %s" % namespace if namespace else "",
        ipv6, count)
    cont_services = service.get_container_by_hostname(container)
    cont_services.try_command_blocking(cmd)

# Starts a modified echo server at given container
# for every input line, the server will echo the same line
# and will append a $ sign at the end of the line.
def start_server(container, address, port):
    cont_services = service.get_container_by_hostname(container)

    # namespace has a random name, vmXXXX
    namespace = cont_services.exec_command('ip netns')

    # interface inside the ns has a random name
    interface = cont_services.exec_command("/bin/sh -c 'ip netns exec %s ip l | grep UP | cut -d: -f2'" % namespace)

    # optional: install ethtool
    #cont_services.try_command_blocking("sh -c 'apt-get update && apt-get -y install ethtool'")

    # disable TCP checksums on interface
    cont_services.try_command_blocking("ip netns exec %s ethtool -K %s tx off rx off" % (
        namespace, interface))

    # launch netcat server in namespace
    cmd = "/bin/sh -c \"ip netns exec %s /usr/bin/ncat -v -4 -l %s %d -k -e '/bin/cat -E'\"" % (namespace, address, port)
    cont_services.exec_command(cmd, stream=True, detach=True)

def stop_server(container):
    cont_services = service.get_container_by_hostname(container)
    namespace = cont_services.exec_command('ip netns')
    pid = cont_services.exec_command('/bin/sh -c "ip netns exec %s netstat -ntlp | grep ncat | awk \'{print $7}\' | cut -d/ -f1"' % namespace)
    cont_services.try_command_blocking("kill %s" % pid)

def client_prepare(container, namespace):
    cont_services = service.get_container_by_hostname(container)

    # optional: install ethtool
    # cont_services.try_command_blocking("sh -c 'apt-get update && apt-get -y install ethtool'")

    # disable TCP checksums
    cont_services.try_command_blocking("ip netns exec ip6 ethtool -K ip6ns tx off rx off")

def client_launch(container, address, server_port, client_port, namespace, count=100):

    # client writes "<host>:<counter>\n" to server every 0.2 secs
    # WARNING: don't remove the delay or fragmentation can occur
    loop_cmd = "for i in `seq 1 %d` ; do echo \"%s:$i\" ; sleep 0.2 ; done" % (
                        count,
                        container)
    ns_cmd = "ip netns exec %s" % namespace
    ncat_cmd = "%s /bin/nc -6 -p %d %s %d" % (ns_cmd if namespace else "",
        client_port, address, server_port)
    cmd = "/bin/sh -c '%s | %s'" % (loop_cmd, ncat_cmd)
    cont_services = service.get_container_by_hostname(container)
    output = cont_services.exec_command(cmd, stream=True)
    return output[0]

def client_wait_for_termination(container, server_port):
    cont_services = service.get_container_by_hostname(container)
    tries = 12
    delay_seconds = 10
    for i in range(tries):
        time.sleep(delay_seconds)
        if (len(cont_services.exec_command("pidof nc")) < 2):
            break
    else:
        raise Exception('TCP client at %s did not terminate within %d seconds' %
                        (container, tries * delay_seconds))

def client_check_result(container, stream, count=100):
    # read stream and break into lines
    lines = reduce(list.__add__, [ i.split() for i in stream ])
    LOG.info("%s: RESULT: got %d lines" % (container, len(lines)))
    assert (len(lines) == count)
    for i in range(count):
        # server must've echoed the line back to us with a dollar sign at the end
        wanted = "%s:%d$" % (container, i+1)
        LOG.info("%s: RESULT: lines[%d] = %s" % (container, i, lines[i]))
        assert(lines[i] == wanted)

@attr(version="v1.2.0")
@bindings(binding_empty,
          binding_manager=BindingManager(vtm=UplinkWithVPP()))
def test_uplink_ipv6():
    """
    Title: ping ipv6 uplink of midolman1 from quagga1. VPP must respond
    """
    ping_from_inet('quagga1', '2001::1', 10)


@attr(version="v1.2.0")
@bindings(binding_multihost_singletenant,
          binding_manager=BindingManager(vtm=SingleTenantAndUplinkWithVPP()))
def test_ping_vm_ipv6():
    """
    Title: ping a VM in a IPv4 neutron topology from a remote IPv6 endpoint
    """
    ping_from_inet('quagga1', 'cccc:bbbb::2', 10, namespace='ip6')


@attr(version="v1.2.0")
@bindings(binding_multihost_multitenant,
          binding_manager=BindingManager(vtm=MultiTenantAndUplinkWithVPP()))
def test_ping_multi_vms_ipv6():
    """
    Title: ping two VMs in a IPv4 neutron topology from a remote IPv6 endpoint
    """
    ping_from_inet('quagga1', 'cccc:bbbb::2', 10, namespace='ip6')
    ping_from_inet('quagga1', 'cccc:cccc::2', 10, namespace='ip6')

@attr(version="v1.2.0")
@bindings(binding_multihost_singletenant_neutronfip6,
          binding_manager=BindingManager(vtm=SingleTenantWithNeutronIPv6FIP()))
def test_neutron_fip6():
    """
    Title: create and associates a IPv6 FIP in neutron checking connectivity
    """
    ping_from_inet('quagga1', 'cccc:bbbb::3', 10, namespace='ip6')

BM=BindingManager(vtm=MultiTenantAndUplinkWithVPP())
@attr(version="v1.2.0")
@bindings(binding_multihost_multitenant,
          binding_manager=BM)
def test_client_server_ipv6():
    """
    Title: Multiple concurrent clients to FIP TCP server.
    """

    # start a netcat server in midolman2
    start_server('midolman2', '0.0.0.0', TCP_SERVER_PORT)

    # configure clients in quagga 1 and 2
    hosts = ['quagga1', 'quagga2']
    namespace = 'ip6'
    for host in hosts:
        client_prepare(host, namespace)

    # launch clients in parallel
    result = {}
    for host in hosts:
        result[host] = client_launch(host, 'cccc:bbbb::2', TCP_SERVER_PORT,
                                     TCP_CLIENT_PORT, namespace)

    # check server response
    for host in hosts:
        client_wait_for_termination(host, TCP_SERVER_PORT)
        client_check_result(host, result[host])

    stop_server('midolman2')

