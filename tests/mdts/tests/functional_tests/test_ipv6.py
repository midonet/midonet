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

    def cleanup_remote_host(self, container, interface):
        cont = service.get_container_by_hostname(container)
        cont.exec_command('ip r del 100.0.0.0/8')
        cont.exec_command('ip a del 2001::2/64 dev %s' % interface)
        cont.exec_command('ip -6 r del cccc:bbbb::/32')
        cont.exec_command('ip -6 r del cccc:cccc::/32')

        cont.exec_command('ip netns delete ip6')

    def setup_remote_host(self, container, interface):
        self.addCleanup(self.cleanup_remote_host, container, interface)
        cont = service.get_container_by_hostname(container)

        cont.try_command_blocking('ip r add 100.0.0.0/8 via 10.1.0.1')
        cont.try_command_blocking('ip a add 2001::2/64 dev %s' % interface)
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
            'ip a add bbbb::1/48 dev ip6dp')
        cont.try_command_blocking(
            'ip netns exec ip6 ip a add bbbb::2/48 dev ip6ns')
        cont.try_command_blocking(
            'ip netns exec ip6 ip -6 r add default via bbbb::1')

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

    @staticmethod
    def await_vpp_running(host_name, timeout = 180):
        cmd = "vppctl show version"
        container = service.get_container_by_hostname(host_name)
        curr_moment = time.time()
        while timeout > 0:
            (ret, output) = container.exec_command_blocking(cmd,
                                                            stderr = True,
                                                            return_output = True)
            for line in output:
                if "vpp v16.09-midonet" in line:
                     return
            next_moment = time.time()
            timeout -= (next_moment - curr_moment)
            curr_moment = next_moment
        raise RuntimeError("Timed out waiting vpp")


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
        self.setup_remote_host('quagga1', 'bgp1')

        self.flush_neighbours('quagga1', 'bgp1')
        self.flush_neighbours('midolman1', 'bgp0')
        self.add_back_route('midolman1')

    # So VPP always know where to send egress packets
    def add_back_route(self, container = 'midolman1'):
        #VPP is lunched by midolman and this might take some time
        # TODO: something better should be done to wait VPP running
        #time.sleep(20)
        uplink_port_id = self.get_mn_uplink_port_id(self.uplink_port)
        uplink_port_name = 'vpp-' + uplink_port_id[0:8]
        NeutronVPPTopologyManagerBase.await_vpp_running(container, 180)
        self.add_route_to_vpp(container,
                              prefix='::/0',
                              via='2001::2',
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
        mn_tenant_downlink = self.add_mn_router_port(
                                                name,
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
        time.sleep(10)

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

class SingleTenantWithNeutronIPv6FIP(SingleTenantAndUplinkWithVPP):

    def build(self, binding_data=None):
        super(SingleTenantAndUplinkWithVPP, self).build(binding_data)

        self.pubnet = self.create_network("public", external=True)
        pubsubnet = self.create_subnet("publicsubnet",
                                            self.pubnet,
                                            "100.0.0.0/8",
                                            version=4)

        pubsubnet6 = self.create_subnet("publicsubnet6",
                                            self.pubnet,
                                            "cccc:bbbb::/32",
                                            version=6)

        uplink_port_id = self.get_mn_uplink_port_id(self.uplink_port)
        uplink_port_name = 'vpp-' + uplink_port_id[0:8]

        # add to the edge router
        self.add_router_interface(self._edgertr, subnet=pubsubnet)

        global DOWNLINK_VETH_MAC

        self.vrf = self.addTenant('tenant', self.pubnet, pubsubnet, uplink_port_name, DOWNLINK_VETH_MAC, 'port1')

        self.addAndAssociateIPv6Fip('cccc:bbbb::3', self.port1)

    def addAndAssociateIPv6Fip(self, fip_address, port):
        fip = self.create_resource(
                self.api.create_floatingip(
                    {'floatingip':
                        {'floating_network_id': self.pubnet['id'],
                         'floating_ip_address': fip_address,
                         'port_id': port['id'],
                         'tenant_id': 'admin'
                        }}
                )
            )
        self.setup_fip64("midolman1",
                         ip6ext = 'bbbb::2',
                         ip6fip = fip_address,
                         ip4fip64 = "20.0.0.65",
                         ip4fixed = "20.0.0.2",
                         tableId = self.vrf)

        return fip['floatingip']

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
@bindings(binding_empty,
          binding_manager=BindingManager(vtm=SingleTenantWithNeutronIPv6FIP()))
@nottest
def test_neutron_fip():
    """
    Title: create and associates a IPv6 FIP in neutron checking connectivity
    """
    ping_from_inet('quagga1', 'cccc:bbbb::3', 10, namespace='ip6')
