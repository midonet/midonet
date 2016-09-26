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

LOG = logging.getLogger(__name__)

UPLINK_VETH_MAC = '2e:0e:2f:68:00:11'
DOWNLINK_VETH_MAC = '2e:0e:2f:68:00:22'

# Neutron topology with a single tenant and uplink
# configured
class SingleTenantAndUplinkWithVPP(NeutronTopologyManager):
    def build(self, binding_data=None):
        # build internal network
        pubnet = self.create_network("public", external=True)
        pubsubnet = self.create_subnet("publicsubnet",
                                       pubnet, "100.0.0.0/8")
        tenantrtr = self.create_router("tenant")
        self.set_router_gateway(tenantrtr, pubnet)

        privnet = self.create_network("private")
        privsubnet = self.create_subnet("privatesubnet",
                                        privnet, "20.0.0.0/26")
        self.add_router_interface(tenantrtr, subnet=privsubnet)

        port1 = self.create_port("port1", privnet)

        # build uplink
        edgertr = self.create_router("edge")
        self.add_router_interface(edgertr, subnet=pubsubnet)
        uplinknet = self.create_network("uplink", uplink=True)
        uplinksubnet = self.create_subnet("uplinksubnet", uplinknet,
                                          "10.1.0.0/24", enable_dhcp=False)
        uplinkport = self.create_port("uplinkport", uplinknet,
                                      host_id="midolman1",
                                      interface="bgp0",
                                      fixed_ip="10.1.0.1")
        self.add_router_interface(edgertr, port=uplinkport)
        self.create_sg_rule(port1['security_groups'][0])

        # wire up vpp
        global UPLINK_VETH_MAC, DOWNLINK_VETH_MAC
        self.create_veth_pair('midolman1', 'uplink-vpp', 'uplink-ovs',
                              ep1mac=UPLINK_VETH_MAC)
        self.create_veth_pair('midolman1', 'downlink-vpp', 'downlink-ovs',
                              ep1mac=DOWNLINK_VETH_MAC)
        self.add_port_to_ovs('midolman1', 'uplink-ovs')

        ## downlink is added to ovs by normal binding
        self.create_ipv6_flows('midolman1', 'bgp0', 'uplink-ovs')

        self.start_vpp('midolman1')
        self.add_port_to_vpp('midolman1',
                             port='uplink-vpp',
                             mac=UPLINK_VETH_MAC,
                             address='2001::1/64')
        self.add_port_to_vpp('midolman1',
                             port='downlink-vpp',
                             mac=DOWNLINK_VETH_MAC,
                             address='10.0.0.1/24')
        self.add_route_to_vpp('midolman1',
                              prefix='bbbb::/48',
                              via='2001::2',
                              port='uplink-vpp')
        self.add_route_to_vpp('midolman1',
                              prefix='20.0.0.0/26',
                              via='10.0.0.2',
                              port='downlink-vpp')
        # setup quagga1
        self.setup_remote_host('quagga1', 'bgp1')

        # hook up downlink to topology
        mn_tenant_downlink = self.add_mn_router_port(
            "downlink", tenantrtr['id'], "10.0.0.2", "10.0.0.0", 24)
        self.add_mn_route(tenantrtr['id'], "0.0.0.0/0", "10.0.0.0/24",
                          port=mn_tenant_downlink.get_id())
        self.add_mn_route(tenantrtr['id'], "0.0.0.0/0", "20.0.0.64/26",
                          via="10.0.0.1",
                          port=mn_tenant_downlink.get_id())

        self.setup_map_t("midolman1",
                         ip4prefix="20.0.0.0/24",
                         ip6prefix="bbbb::/48",
                         ip6src="cccc:bbbb::/96")

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
        cont.exec_command('service vpp stop')

    def start_vpp(self, container):
        cont = service.get_container_by_hostname(container)
        self.addCleanup(self.stop_vpp, container)
        cont.exec_command('service vpp restart') # kills current vpp if exists

    def del_port_from_vpp(self, container, port):
        cont = service.get_container_by_hostname(container)
        try:
            cont.vppctl('delete host-interface name %s' % port)
        except:
            LOG.error("Erroring deleting interface %s from vpp" % port)

    def add_port_to_vpp(self, container, port, mac, address):
        cont = service.get_container_by_hostname(container)
        self.addCleanup(self.del_port_from_vpp, container, port)
        cont.vppctl('create host-interface name %s hw-addr %s' % (port, mac))
        cont.vppctl('set int state host-%s up' % port)
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

    def setup_map_t(self, container, ip4prefix, ip6prefix, ip6src):
        cont = service.get_container_by_hostname(container)
        cont.vppctl("map add domain ip4-pfx " + ip4prefix
                    + "ip6-pfx " + ip6prefix + " ea-bits-len 8"
                    + " psid-offset 0 psid-len 0 ip6-src " + ip6src +" map-t")

    def cleanup_remote_host(self, container, interface):
        cont = service.get_container_by_hostname(container)
        cont.exec_command('ip r del 100.0.0.0/8')
        cont.exec_command('ip a del 2001::2/64 dev %s' % interface)
        cont.exec_command('ip -6 r del cccc:bbbb::/32')

        cont.exec_command('ip netns delete ip6')

    def setup_remote_host(self, container, interface):
        self.addCleanup(self.cleanup_remote_host, container, interface)
        cont = service.get_container_by_hostname(container)

        cont.try_command_blocking('ip r add 100.0.0.0/8 via 10.1.0.1')
        cont.try_command_blocking('ip a add 2001::2/64 dev %s' % interface)
        cont.try_command_blocking('ip -6 r add cccc:bbbb::/32 via 2001::1')

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
            'ip a add bbbb::4100:0:1400:41:1/48 dev ip6dp')
        cont.try_command_blocking(
            'ip netns exec ip6 ip a add bbbb::4100:0:1400:41:0/48 dev ip6ns')
        cont.try_command_blocking(
            'ip netns exec ip6 ip -6 r add default via bbbb::4100:0:1400:41:1')

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



VTM = SingleTenantAndUplinkWithVPP()
BM = BindingManager(vtm=VTM)

binding_multihost = {
    'description': 'spanning across 2 midolman',
    'bindings': [
         {'vport': 'port1',
         'interface': {
             'definition': { "ipv4_gw": "20.0.0.1" },
             'hostname': 'midolman2',
             'type': 'vmguest'
         }},
         {'vport': 'downlink',
         'interface': {
             'definition': { 'ifname': 'downlink-ovs' },
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

@attr(version="v1.2.0")
@bindings(binding_multihost,
          binding_manager=BM)
def test_uplink_ipv6():
    """
    Title: ping ipv6 uplink of midolman1 from quagga1. VPP must respond

    """
    ping_from_inet('quagga1', '2001::1', 4)


@attr(version="v1.2.0")
@bindings(binding_multihost,
          binding_manager=BM)
def test_ping_vm_ipv6():
    """
    Title: ping a VM in a IPv4 neutron topology from a remote IPv6 endpoint
    """
    ping_from_inet('quagga1', 'cccc:bbbb::1400:2', 4, namespace='ip6')
