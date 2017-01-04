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

import logging
from mdts.lib.bindings import BindingManager
from mdts.lib.vtm_neutron import NeutronTopologyManager
from mdts.services import service
from mdts.tests.utils.utils import bindings
from nose.plugins.attrib import attr
import re
import subprocess
import time
import uuid

LOG = logging.getLogger(__name__)

UPLINK_VETH_MAC = '2e:0e:2f:68:00:11'
DOWNLINK_VETH_MAC = '2e:0e:2f:68:00:22'
DOWNLINK_VETH_MAC_2 = '2e:0e:2f:68:00:33'
TCP_SERVER_PORT = 9999
TCP_CLIENT_PORT = 9998
LRU_SIZE_SINGLE_TENANT = 2

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
        self.add_vpp_ip_address(cont, port, address)

    def add_vpp_ip_address(self, cont_services, port, address):
        self.addCleanup(self.del_vpp_ip_address, cont_services, port, address)
        cont_services.vppctl('set int ip address host-%s %s' % (port, address))

    def del_vpp_ip_address(self, cont_services, port, address):
        cont_services.vppctl('set int ip address del host-%s %s' % (port, address))

    def del_route_from_vpp(self, container, prefix, via, port, vrf):
        cont = service.get_container_by_hostname(container)
        try:
            cont.vppctl('ip route del %s table %s' % (prefix, vrf))
        except:
            LOG.error("Erroring deleting route %s from vpp in table %s" % (prefix, vrf))

    def add_route_to_vpp(self, container, prefix, via, port, vrf = 0):
        cont = service.get_container_by_hostname(container)
        self.addCleanup(self.del_route_from_vpp, container, prefix, via, port, vrf)
        cont.vppctl('ip route add %s via %s host-%s table %s' % (prefix, via, port, vrf))

    def setup_fip64(self, container, ip6fip, ip4fixed,
                    ip4PoolStart, ip4PoolEnd, tableId=0):
        cont = service.get_container_by_hostname(container)
        fip64DelCmd = "fip64 del %s" % ip6fip
        self.addCleanup(cont.vppctl, fip64DelCmd)
        vni = 0x123456 # unused
        cont.vppctl("fip64 add %s %s pool %s %s table %d vni %d" % (ip6fip,
                                                                    ip4fixed,
                                                                    ip4PoolStart,
                                                                    ip4PoolEnd,
                                                                    tableId,
                                                                    vni))

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
            except RuntimeError:
                pass
            timeout += curr_moment
            curr_moment = time.time()
            timeout -= curr_moment
        raise RuntimeError("Timed out waiting vpp to initialize")


class UplinkWithVPP(NeutronVPPTopologyManagerBase):

    def __init__(self):
        super(UplinkWithVPP, self).__init__()
        self.uplink_ports = []
        self.vrf = 2

    #param: cidr IPv6 subnet. Not any address will work, as remote_host_
    #       setup adds route only for 2001::1
    def build_uplink(self, cidr, host, interface):
        id = int(cidr[0])
        uplinknet = self.create_network("uplink%d" % id, uplink=True)
        (net_pref, prefix_len) = cidr.split('/')
        ipv6_gw_ip = net_pref + "1"
        self.create_subnet("uplinksubnet6_%d" % id, uplinknet, cidr,
                           enable_dhcp=False, version=6)
        uplink_port = self.create_port("uplinkport%d" % id, uplinknet,
                                       host_id=host,
                                       interface=interface,
                                       fixed_ips=[ipv6_gw_ip])

        self.add_router_interface(self._edgertr, port=uplink_port)
        self.uplink_ports.append(uplink_port)

    def flush_all(self):
        self.flush_neighbours('quagga1', 'bgp1')
        self.flush_neighbours('quagga2', 'bgp2')
        self.flush_neighbours('midolman1', 'bgp0')

    def build(self, binding_data=None):
        self._edgertr = self.create_router("edge")
        self.build_uplink("2001::/64", "midolman1", "bgp0")

        # setup quagga1
        self.setup_remote_host('quagga1', 'bgp1',
            gw_address="2001::2",
            local_address="bbbb::2",
            local_router="bbbb::1")

        self.setup_remote_host('quagga2', 'bgp2',
            gw_address="2001::3",
            local_address="eeee::2",
            local_router="eeee::1")

        self.flush_all()
        uplink_port_id = self.get_mn_uplink_port_id(self.uplink_ports[0])
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
                              port=dlink_vpp_name,
                              vrf=self.vrf)

        # hook up downlink to topology
        mn_tenant_downlink = self.add_mn_router_port(name,
                                                     tenantrtr['id'],
                                                     "10.0.0.2",
                                                     "10.0.0.0",
                                                     24)
        self.add_mn_route(tenantrtr['id'],
                          src_prefix="0.0.0.0/0",
                          dst_prefix="10.0.0.0/24",
                          port=mn_tenant_downlink.get_id())
        self.add_mn_route(tenantrtr['id'],
                          src_prefix="0.0.0.0/0",
                          dst_prefix="20.0.0.64/26",
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
        lru_start = 65
        lru_end = lru_start + LRU_SIZE_SINGLE_TENANT - 1
        self.setup_fip64("midolman1",
                         ip6fip='cccc:bbbb::2',
                         ip4fixed="20.0.0.2",
                         ip4PoolStart="20.0.0.%d" % lru_start,
                         ip4PoolEnd="20.0.0.%d" % lru_end,
                         tableId=vrf)


class SingleTenantWithNeutronIPv6FIP(UplinkWithVPP):

    def build_public_subnets(self):
        pubsubnet6 = self.create_subnet("publicsubnet6",
                                        self.pubnet,
                                        "cccc:bbbb::/32",
                                        version=6)
        return [pubsubnet6]

    def build_tenant(self):
        self.pubnet = self.create_network("public", external=True)

        # add public subnets to the edge router
        pubsubnets = self.build_public_subnets()
        map(lambda sn: self.add_router_interface(self._edgertr, subnet=sn),
            pubsubnets)

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

        self.fip6 = self.create_resource(
            self.api.create_floatingip(
                {'floatingip':
                    {'floating_network_id': self.pubnet['id'],
                     'floating_ip_address': 'cccc:bbbb::3',
                     'port_id': self.port1['id'],
                     'tenant_id': 'admin'}
                }
            )
        )

    def build(self, binding_data=None):
        super(SingleTenantWithNeutronIPv6FIP, self).build(binding_data)
        self.build_tenant()

class FIP6Reuse(SingleTenantWithNeutronIPv6FIP):

    def build(self, binding_data=None):
        super(FIP6Reuse, self).build(binding_data)
        privnet = self.get_resource('private-tenant')['network']
        self.port2 = self.create_port('port2', privnet)
        fip6id = self.fip6['floatingip']['id']
        self.api.update_floatingip(fip6id, {'floatingip': {'port_id': None }})
        self.api.update_floatingip(fip6id,
            {'floatingip': {'port_id': self.port2['id'] } }
        )
        self.addCleanup(self.api.update_floatingip,
            fip6id, {'floatingip': {'port_id': None }})

class TenantDualStack(SingleTenantWithNeutronIPv6FIP):

    def build_public_subnets(self):
        pubsubnets = super(TenantDualStack, self).build_public_subnets()
        pubsubnet4 = self.create_subnet("publicsubnet4",
                                        self.pubnet,
                                        "200.0.0.0/24",
                                        version=4)
        pubsubnets.append(pubsubnet4)
        return pubsubnets

class DualUplinkAssymetric(SingleTenantWithNeutronIPv6FIP):

    def build(self, binding_data=None):
        self._edgertr = self.create_router("edge")
        self.build_uplink("2001::/64", "midolman1", "bgp0")
        self.build_uplink("3001::/64", "midolman2", "bgp0")

        self.setup_remote_host('quagga1', 'bgp1',
            gw_address="2001::2",
            local_address="bbbb::2",
            local_router="bbbb::1")
        self.set_gateway_address('quagga1', "3001::2/64", "bgp2")
        self.flush_all()
        self.flush_neighbours('quagga1', 'bgp2')
        self.flush_neighbours('midolman2', 'bgp0')

        uplink_port_id = self.get_mn_uplink_port_id(self.uplink_ports[1])
        uplink_port_name = 'vpp-' + uplink_port_id[0:8]
        self.await_vpp_initialized('midolman2', uplink_port_name, 180)
        self.add_route_to_vpp('midolman2',
                              prefix='bbbb::/64',
                              via='3001::2',
                              port=uplink_port_name)
        self.build_tenant()

    def unset_gateway_address(self, cont_services, gw_address, interface):
        cont_services.exec_command('ip a del %s dev %s' % (gw_address, interface))

    def set_gateway_address(self, container, gw_address, interface):
        cont_services = service.get_container_by_hostname(container)
        self.addCleanup(self.unset_gateway_address, cont_services, gw_address, interface)
        cont_services.exec_command('ip a add %s dev %s' % (gw_address, interface))

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
                         ip6fip='cccc:bbbb::2',
                         ip4fixed="20.0.0.2",
                         ip4PoolStart="20.0.0.65",
                         ip4PoolEnd="20.0.0.67",
                         tableId=vrf)

        vrf = self.addTenant('tenant2',
                             pubnet,
                             DOWNLINK_VETH_MAC_2,
                             'port2')
        self.setup_fip64("midolman1",
                         ip6fip='cccc:cccc::2',
                         ip4fixed="20.0.0.2",
                         ip4PoolStart="20.0.0.65",
                         ip4PoolEnd="20.0.0.67",
                         tableId=vrf)

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
    'description': 'spanning across 2 midolmans',
    'bindings': [
         {'vport': 'port1',
         'interface': {
             'definition': {"ipv4_gw": "192.168.0.1"},
             'hostname': 'midolman2',
             'type': 'vmguest'
         }}
    ]
}

binding_fip6reuse = {
    'description': 'spanning across 2 midolmans',
    'bindings': [
         {'vport': 'port1',
         'interface': {
             'definition': {"ipv4_gw": "192.168.0.1"},
             'hostname': 'midolman2',
             'type': 'vmguest'
         }},
         {'vport': 'port2',
         'interface': {
             'definition': {"ipv4_gw": "192.168.0.1"},
             'hostname': 'midolman1',
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


# Runs ping command with given ip address from given containter
# Count provides the number of packets ping will send.
# The number must be positive
def ping_from_inet(container, ip='2001::1', count=4, namespace=None):
    count = max(1, count)
    ping_cmd = "ping6"
    cmd = "%s %s %s -c %d" % (
        "ip netns exec %s" % namespace if namespace else "",
        ping_cmd, ip, count)
    cont_services = service.get_container_by_hostname(container)
    try:
        cont_services.try_command_blocking(cmd)
    except:
        import ipdb; ipdb.set_trace()


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

    # disable TCP checksums
    cont_services.try_command_blocking("ip netns exec %s ethtool -K ip6ns tx off rx off" % namespace)


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
    lines = reduce(list.__add__, [i.split() for i in stream])
    LOG.info("%s: RESULT: got %d lines" % (container, len(lines)))
    assert (len(lines) == count)
    for i in range(count):
        # server must've echoed the line back to us with a dollar sign at the end
        wanted = "%s:%d$" % (container, i + 1)
        LOG.info("%s: RESULT: lines[%d] = %s" % (container, i, lines[i]))
        assert(lines[i] == wanted)

class TCPSessionClient:
    instance_id = 0
    def __init__(self, container, gateway, address, prefix, server, port):
        ns = 'client%d' % self.__class__.instance_id
        self.ns = ns
        self.container = container
        self.__class__.instance_id += 1
        cont = service.get_container_by_hostname(container)
        cont.exec_command('ip netns add %s' % ns)
        cont.try_command_blocking(
            'ip l add name %sdp type veth peer name %sns' % (ns, ns))
        cont.try_command_blocking('ip l set netns %s dev %sns' % (ns, ns))
        cont.try_command_blocking('ip l set up dev %sdp' % ns)
        cont.try_command_blocking('ip a add %s/%d dev %sdp' % (gateway, prefix, ns))
        cont.try_command_blocking('ip netns exec %s ip link set up dev lo' % ns)
        cont.try_command_blocking('ip netns exec %s ip link set up dev %sns' % (ns,ns))
        cont.try_command_blocking(
            'ip netns exec %s ip a add %s/%d dev %sns' % (ns, address, prefix, ns))
        cont.try_command_blocking(
            'ip netns exec %s ip -6 r add default via %s' % (ns, gateway))

        cmd = 'ip netns exec %s /bin/nc %s %d' % (ns, server, port)
        self.counter = 0
        self.io = None
        self.cmd = cmd.split()

    def start(self):
        self.io = DockerIOExecutor(self.container, self.cmd)

    def verify(self):
        try:
            self.counter += 1
            msg = '%s:%d' % (self, self.counter)
            output = self.io.request(msg)
            LOG.debug('Sent: ' + msg)
            LOG.debug('Out: ' + output)
            return output == msg + "$\n"
        except:
            return False

    def close(self):
        cont = service.get_container_by_hostname(self.container)
        cont.try_command_blocking('ip link del %sdp' % self.ns)
        cont.try_command_blocking('ip netns del %s'% self.ns)
        try:
            cont.try_command_blocking('pkill -f nc')
        except:
            pass
        self.io.close()

class DockerIOExecutor:
    def __init__(self, container, command):
        cont = service.get_container_by_hostname(container)
        container = cont.get_name()
        cmdline = ['docker', 'exec', '-i', container] + command
        LOG.debug('DockerIOExecutor. command=%s' % cmdline)
        self.subprocess = subprocess.Popen(cmdline,
                                           stdin=subprocess.PIPE,
                                           stdout=subprocess.PIPE,
                                           stderr=subprocess.PIPE)

    def request(self, msg):
        self.subprocess.stdin.write(msg + '\n')
        self.subprocess.stdin.flush()
        return self.subprocess.stdout.readline()

    def close(self):
        self.subprocess.terminate()


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

@attr(version="v1.2.0")
@bindings(binding_fip6reuse,
          binding_manager=BindingManager(vtm=FIP6Reuse()))
def test_fip6reuse():
    """
    Title: create and reassociates a IPv6 FIP in neutron. Ping must work.
    """
    ping_from_inet('quagga1', 'cccc:bbbb::3', 10, namespace='ip6')

BM = BindingManager(vtm=MultiTenantAndUplinkWithVPP())


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

@attr(version="v1.2.0")
@bindings(binding_multihost_singletenant,
          binding_manager=BindingManager(vtm=SingleTenantAndUplinkWithVPP()))
def test_lru():
    """
    Title: Test LRU behaviour
    """

    clients = []
    extra = None

    try:
        # start a netcat server in midolman2
        start_server('midolman2', '0.0.0.0', TCP_SERVER_PORT)

        # launch clients
        fip = 'cccc:bbbb::2'
        container = 'quagga1'

        cont = service.get_container_by_hostname(container)

        address_counter = [0]
        def new_client():
            address_counter[0] += 1
            idx = address_counter[0]
            prefix = 112
            gateway = 'bbbb::%d:1' % idx
            address = 'bbbb::%d:2' % idx
            return TCPSessionClient(container, gateway, address, prefix, fip, TCP_SERVER_PORT)

        # validate existing clients
        for i in range(LRU_SIZE_SINGLE_TENANT):
            client = new_client()
            clients.append( client )
            ping_from_inet(container, fip, 10, namespace=client.ns)
            client.start()
            assert(client.verify())
            time.sleep(1)

        for client in clients:
            assert(client.verify())
            time.sleep(1)

        # connect one more client
        extra = new_client()
        ping_from_inet(container, fip, 10, namespace=extra.ns)
        extra.start()
        assert(extra.verify())
        time.sleep(1)

        # this must've disconnected clients[0], the least recently used.
        # We don't check client[0] directly, as this will make it steal
        # another address and cause more disconnections
        for client in clients[1:]:
            assert(client.verify())
        assert(extra.verify())
        assert(False == clients[0].verify())

    finally:
        stop_server('midolman2')
        for client in clients:
            if client is not None:
                client.close()
        if extra is not None:
            extra.close()

@attr(version="v1.2.0")
@bindings(binding_multihost_singletenant_neutronfip6,
          binding_manager=BindingManager(vtm=DualUplinkAssymetric()))
def test_2uplinks_assymetric():
    """
    Title: Provider router with two uplinks. Only second uplink VPP has egress route for IPV6 traffic
    """
    ping_from_inet('quagga1', 'cccc:bbbb::3', 10, namespace='ip6')

@attr(version="v1.2.0")
@bindings(binding_multihost_singletenant_neutronfip6,
          binding_manager=BindingManager(vtm=SingleTenantWithNeutronIPv6FIP()))
def test_fragments():

    """
    Title: send fragmented packets to and from IPv6 clients
    """

    # This tests sends a ~30KB UDP packet fragmented in 1K chunks
    # from IPv6 client at quagga1 to IPv4 server at midolman2, and then
    # the other way around

    CLIENT_PY_NAME='frag-client.py'
    SERVER_PY_NAME='frag-server.py'

    def kill(container, pattern, signal=''):
        try:
            container.try_command_blocking('pkill %s -f %s' % (signal, pattern))
            return True
        except:
            return False

    def is_running(container, pattern):
        return kill(container, pattern, signal='-0')

    def get_output(stream):
        try:
            result = {}
            lines = reduce(list.__add__, [i.split() for i in stream])
            for line in lines:
                if '=' in line:
                    parts = line.split('=')
                    if len(parts) == 2:
                        result[parts[0]] = parts[1]
            return result
        except:
            raise Exception('No output')

    # sends one fragmented packet from client to server, and validates
    # that both have the same packet by checking the hash
    def run_fragmentation_test(client, clientns, server, serverns,
                               server_addr, client_addr, listen_addr):
        srv_out = server.exec_command('ip netns exec %s python %s %s' % (
            serverns, SERVER_PY_NAME, listen_addr), detach=False, stream=True)

        # need to send the fragmented packet a few times, because there might
        # be fragments lost. It usually succeeds at the second attempt
        for i in range(10):
            time.sleep(2)
            if is_running(server, SERVER_PY_NAME):
                cli_out = client.exec_command('ip netns exec %s python %s %s %s' % (
                    clientns, CLIENT_PY_NAME, client_addr, server_addr),
                    detach=False, stream=True)
            else:
                # the server stopped after receiving a full packet
                break
        else:
            kill(server, SERVER_PY_NAME)

        # make sure client has terminated before reading its output
        for i in range(10):
            if not is_running(client, CLIENT_PY_NAME):
                break
        else:
            kill(client, CLIENT_PY_NAME)

        client = get_output(cli_out[0])
        server = get_output(srv_out[0])

        assert(client['HASH'] == server['HASH'])
        return server['CLIENT']

    # first warm up the network by sending some pings
    ping_from_inet('quagga1', 'cccc:bbbb::3', 10, namespace='ip6')

    midolman2 = service.get_container_by_hostname('midolman2')
    quagga1 = service.get_container_by_hostname('quagga1')

    try:
        # sends a fragmented UDP packet. Use IP version 4 or 6 depending of
        # the passed addresses
        client_py = """
import hashlib
from scapy.all import *
import sys
source=sys.argv[1]
target=sys.argv[2]
payload=''.join(struct.pack('!H',i) for i in range(16000))
if '.' in target:
    packet=IP(dst=target,src=source)/UDP(sport=1500,dport=9999)/payload
    frags=fragment(packet, 1024)
else:
    packet=IPv6(dst=target,src=source)/IPv6ExtHdrFragment()/UDP(sport=1500,dport=9999)/payload
    frags=fragment6(packet, 1024)
for f in frags: send(f)
print 'HASH=' + hashlib.sha256(payload).hexdigest()
        """

        # waits until reception of an UDP packet and prints its hash
        # and the address of the sender
        server_py = """
import hashlib
import socket
import sys
bind_addr = sys.argv[1]
family = socket.AF_INET if '.' in bind_addr else socket.AF_INET6
sock = socket.socket(family, socket.SOCK_DGRAM)
sock.bind((bind_addr,9999))
data, address = sock.recvfrom(65536)
print 'CLIENT=' + address[0]
print 'HASH=' + hashlib.sha256(data).hexdigest()
        """
        for host in [ quagga1, midolman2 ]:
            host.put_file(CLIENT_PY_NAME, client_py)
            host.put_file(SERVER_PY_NAME, server_py)

        namespace = midolman2.exec_command('ip netns')
        assert(len(namespace.split('\n')) == 1)
        # loopback is required to be UP in midolman's namespace otherwise
        # scapy will fail
        midolman2.exec_command('ip netns exec %s ip l set up dev lo' % namespace)

        # send a fragmented packet from 6 to 4. Save allocated IPv4 address
        ip4client = run_fragmentation_test(quagga1, 'ip6', midolman2, namespace,
                                           "cccc:bbbb::3", "bbbb::2", "0.0.0.0")

        # send a fragmented packet from 4 to 6
        run_fragmentation_test(midolman2, namespace, quagga1, 'ip6',
                               ip4client, '192.168.0.2', "bbbb::2")
    finally:
        for host in [ quagga1, midolman2 ]:
            kill(host, CLIENT_PY_NAME)
            kill(host, SERVER_PY_NAME)
            host.exec_command('rm %s %s' % (CLIENT_PY_NAME, SERVER_PY_NAME))

binding_manager=BindingManager(vtm=TenantDualStack())
@bindings(binding_multihost_singletenant_neutronfip6,
          binding_manager=binding_manager)
def test_tenant_dual_stack():
    """
    Title: Tenant router is attached to both ipv4 and ipv6 external subnents.
    """
    ping_from_inet('quagga1', 'cccc:bbbb::3', 10, namespace='ip6')
    vmport = binding_manager.get_interface_on_vport('port1')
    cmd = 'ping -c 10 -i 0.5 200.0.0.1'
    (result, exec_id) = vmport.do_execute(cmd, stream=True)
    retcode = vmport.compute_host.check_exit_status(exec_id, result, timeout=120)
    assert(retcode == 0)
