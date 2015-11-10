# Copyright 2013, 2014 Midokura SARL, All Rights Reserved.
# All Rights Reserved
#
# Licensed under the Apache License, Version 2.0 (the "License"); you may
# not use this file except in compliance with the License. You may obtain
# a copy of the License at
#
# http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
# WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
# License for the specific language governing permissions and limitations
# under the License.

import logging

from midonetclient import application
from midonetclient import auth_lib
from midonetclient import exc

LOG = logging.getLogger(__name__)


def _net_addr(addr):
    """Get network address prefix and length from a given address."""
    nw_addr, nw_len = addr.split('/')
    nw_len = int(nw_len)
    return nw_addr, nw_len


class MidonetApi(object):

    def __init__(self, base_uri, username, password, project_id=None):
        self.base_uri = base_uri.rstrip('/')
        self.project_id = project_id
        self.app = None
        self.auth = auth_lib.Auth(self.base_uri + '/login', username, password,
                                  project_id)

    def get_tenants(self, query={}):
        self._ensure_application()
        return self.app.get_tenants(query)

    def delete_l2insertion(self, id_):
        self._ensure_application()
        return self.app.delete_l2insertion(id_)

    def get_l2insertions(self, query):
        self._ensure_application()
        return self.app.get_l2insertions(query)

    def get_l2insertion(self, id_):
        self._ensure_application()
        return self.app.get_l2insertion(id_)

    def add_l2insertion(self):
        self._ensure_application()
        return self.app.add_l2insertion()

    def delete_router(self, id_):
        self._ensure_application()
        return self.app.delete_router(id_)

    def get_routers(self, query):
        self._ensure_application()
        return self.app.get_routers(query)

    def delete_mirror(self, id_):
        self._ensure_application()
        return self.app.delete_mirror(id_)

    def get_mirrors(self, query):
        self._ensure_application()
        return self.app.get_mirrors(query)

    def delete_bridge(self, id_):
        self._ensure_application()
        return self.app.delete_bridge(id_)

    def get_bridges(self, query):
        self._ensure_application()
        return self.app.get_bridges(query)

    def get_ports(self, query):
        self._ensure_application()
        return self.app.get_ports(query)

    def delete_port_group(self, id_):
        self._ensure_application()
        return self.app.delete_port_group(id_)

    def delete_ip_addr_group(self, id_):
        self._ensure_application()
        return self.app.delete_ip_addr_group(id_)

    def get_port_groups(self, query):
        self._ensure_application()
        return self.app.get_port_groups(query)

    def get_ip_addr_groups(self, query=None):
        if query is None:
            query = {}
        self._ensure_application()
        return self.app.get_ip_addr_groups(query)

    def get_chains(self, query):
        self._ensure_application()
        return self.app.get_chains(query)

    def delete_chain(self, id_):
        self._ensure_application()
        return self.app.delete_chain(id_)

    def get_chain(self, id_):
        self._ensure_application()
        return self.app.get_chain(id_)

    def get_tunnel_zones(self, query=None):
        if query is None:
            query = {}
        self._ensure_application()
        return self.app.get_tunnel_zones(query)

    def get_tunnel_zone(self, id_):
        self._ensure_application()
        return self.app.get_tunnel_zone(id_)

    def get_hosts(self, query=None):
        if query is None:
            query = {}
        self._ensure_application()
        return self.app.get_hosts(query)

    # L4LB resources
    def get_load_balancers(self, query=None):
        if query is None:
            query = {}
        self._ensure_application()
        return self.app.get_load_balancers(query)

    def get_vips(self, query=None):
        if query is None:
            query = {}
        self._ensure_application()
        return self.app.get_vips(query)

    def get_pools(self, query=None):
        if query is None:
            query = {}
        self._ensure_application()
        return self.app.get_pools(query)

    def get_pool_members(self, query=None):
        if query is None:
            query = {}
        self._ensure_application()
        return self.app.get_pool_members(query)

    def get_health_monitors(self, query=None):
        if query is None:
            query = {}
        self._ensure_application()
        return self.app.get_health_monitors(query)

    def get_pool_statistics(self, query=None):
        if query is None:
            query = {}
        self._ensure_application()
        return self.app.get_pool_statistics(query)

    def get_load_balancer(self, id_):
        self._ensure_application()
        return self.app.get_load_balancer(id_)

    def get_vip(self, id_):
        self._ensure_application()
        return self.app.get_vip(id_)

    def get_pool(self, id_):
        self._ensure_application()
        return self.app.get_pool(id_)

    def get_pool_member(self, id_):
        self._ensure_application()
        return self.app.get_pool_member(id_)

    def get_health_monitor(self, id_):
        self._ensure_application()
        return self.app.get_health_monitor(id_)

    def get_pool_statistic(self, id_):
        self._ensure_application()
        return self.app.get_pool_statistic(id_)

    def add_load_balancer(self):
        self._ensure_application()
        return self.app.add_load_balancer()

    def add_vip(self):
        self._ensure_application()
        return self.app.add_vip()

    def add_pool(self):
        self._ensure_application()
        return self.app.add_pool()

    def add_pool_member(self):
        self._ensure_application()
        return self.app.add_pool_member()

    def add_health_monitor(self):
        self._ensure_application()
        return self.app.add_health_monitor()

    def add_pool_statistic(self):
        self._ensure_application()
        return self.app.add_pool_statistic()

    def delete_load_balancer(self, id_):
        self._ensure_application()
        return self.app.delete_load_balancer(id_)

    def delete_vip(self, id_):
        self._ensure_application()
        return self.app.delete_vips(id_)

    def delete_pool(self, id_):
        self._ensure_application()
        return self.app.delete_pool(id_)

    def delete_pool_member(self, id_):
        self._ensure_application()
        return self.app.delete_pool_member(id_)

    def delete_health_monitor(self, id_):
        self._ensure_application()
        return self.app.delete_health_monitor(id_)

    def delete_pool_statistic(self, id_):
        self._ensure_application()
        return self.app.delete_pool_statistic(id_)

    def add_host_interface_port(self, host, port_id, interface_name):
        return host.add_host_interface_port().port_id(port_id) \
            .interface_name(interface_name).create()

    def get_system_state(self):
        self._ensure_application()
        return self.app.get_system_state()

    def get_host(self, id_):
        self._ensure_application()
        return self.app.get_host(id_)

    def delete_bgp_network(self, id_):
        self._ensure_application()
        return self.app.delete_bgp_network(id_)

    def get_bgp_network(self, id_):
        self._ensure_application()
        return self.app.get_bgp_network(id_)

    def delete_bgp_peer(self, id_):
        self._ensure_application()
        return self.app.delete_bgp_peer(id_)

    def get_bgp_peer(self, id_):
        self._ensure_application()
        return self.app.get_bgp_peer(id_)

    def get_bridge(self, id_):
        self._ensure_application()
        return self.app.get_bridge(id_)

    def get_mirror(self, id_):
        self._ensure_application()
        return self.app.get_mirror(id_)

    def get_port_group(self, id_):
        self._ensure_application()
        return self.app.get_port_group(id_)

    def get_ip_addr_group(self, id_):
        self._ensure_application()
        return self.app.get_ip_addr_group(id_)

    def delete_port(self, id_):
        self._ensure_application()
        return self.app.delete_port(id_)

    def get_port(self, id_):
        self._ensure_application()
        return self.app.get_port(id_)

    def delete_route(self, id_):
        self._ensure_application()
        return self.app.delete_route(id_)

    def get_route(self, id_):
        self._ensure_application()
        return self.app.get_route(id_)

    def get_router(self, id_):
        self._ensure_application()
        return self.app.get_router(id_)

    def delete_rule(self, id_):
        self._ensure_application()
        return self.app.delete_rule(id_)

    def get_rule(self, id_):
        self._ensure_application()
        return self.app.get_rule(id_)

    def get_tenant(self, id_):
        self._ensure_application()
        return self.app.get_tenant(id_)

    def add_router(self):
        self._ensure_application()
        return self.app.add_router()

    def add_bridge(self):
        self._ensure_application()
        return self.app.add_bridge()

    def add_mirror(self):
        self._ensure_application()
        return self.app.add_mirror()

    def _set_op121(self, dhcp, rts):
        opt121_list = []
        for rt in rts:
            rt_net_addr, rt_net_len = rt['destination'].split('/')
            opt121_list.append({'destinationPrefix': rt_net_addr,
                                'destinationLength': rt_net_len,
                                'gatewayAddr': rt['nexthop']})
        dhcp.opt121_routes(opt121_list)

    def update_bridge_dhcp(self, bridge, cidr, gateway_ip, host_rts=None,
                           dns_nservers=None, enabled=None):
        mido_cidr = cidr.replace("/", "_")
        dhcp = bridge.get_dhcp_subnet(mido_cidr)
        if dhcp is None:
            return

        if gateway_ip is not None:
            dhcp.default_gateway(gateway_ip)

        if host_rts is not None:
            self._set_op121(dhcp, host_rts)

        if dns_nservers is not None:
            dhcp.dns_server_addrs(dns_nservers)

        if enabled is not None:
            dhcp.enabled(enabled)

        return dhcp.update()

    def add_bridge_dhcp(self, bridge, gateway_ip, cidr, host_rts=None,
                        dns_nservers=None, enabled=True):
        """Creates a dhcp subnet with the provided gateway ip, cidr,
        host routes, and dns name servers.

        :returns: The new dhcp subnet resource.
        :param bridge: Bridge of the new dhcp subnet.
        :param gateway_ip: Single ipv4 address string.
        :param cidr: Subnet represented by cidr notation [ipv4 addr]/[prefix].
        :param host_rts: An array of dictionaries, each of the form:
            {"destination": <ipv4 cidr>, "nexthop": <ipv4 string>}.
        :param dns_nservers: An array of strings representing ipv4 addresses.
        :param enabled: Enable DHCP
        """
        if host_rts is None:
            host_rts = []

        if dns_nservers is None:
            dns_nservers = []

        net_addr, net_len = cidr.split('/')

        dhcp = bridge.add_dhcp_subnet()
        dhcp.default_gateway(gateway_ip)
        dhcp.subnet_prefix(net_addr)
        dhcp.subnet_length(net_len)

        if host_rts:
            self._set_op121(dhcp, host_rts)

        if dns_nservers:
            dhcp.dns_server_addrs(dns_nservers)

        dhcp.enabled(enabled)
        return dhcp.create()

    def add_port_group(self):
        self._ensure_application()
        return self.app.add_port_group()

    def add_ip_addr_group(self):
        self._ensure_application()
        return self.app.add_ip_addr_group()

    def add_chain(self):
        self._ensure_application()
        return self.app.add_chain()

    def add_tunnel_zone(self):
        self._ensure_application()
        return self.app.add_tunnel_zone()

    def add_gre_tunnel_zone(self):
        self._ensure_application()
        return self.app.add_gre_tunnel_zone()

    def add_vxlan_tunnel_zone(self):
        self._ensure_application()
        return self.app.add_vxlan_tunnel_zone()

    def add_vtep_tunnel_zone(self):
        self._ensure_application()
        return self.app.add_vtep_tunnel_zone()

    def add_bridge_port(self, bridge):
        return bridge.add_port()

    def add_router_port(self, router):
        return router.add_port()

    def link(self, port, peer_id):
        port.link(peer_id)

    def unlink(self, port):
        if port.get_peer_id():
            peer_id = port.get_peer_id()
            port.unlink()
            self.delete_port(peer_id)

    def add_router_route(self, router, route_type='Normal',
                         src_network_addr=None, src_network_length=None,
                         dst_network_addr=None, dst_network_length=None,
                         next_hop_port=None, next_hop_gateway=None,
                         weight=100):
        """Add a route to a router."""
        route = router.add_route().type(route_type)
        route = route.src_network_addr(src_network_addr).src_network_length(
            src_network_length).dst_network_addr(
                dst_network_addr).dst_network_length(dst_network_length)
        route = route.next_hop_port(next_hop_port).next_hop_gateway(
            next_hop_gateway).weight(weight)

        return route.create()

    def get_router_routes(self, router_id):
        """Get a list of routes for a given router."""
        router = self.get_router(router_id)
        if router is None:
            raise ValueError("Invalid router_id passed in %s" % router_id)
        return router.get_routes()

    def add_chain_rule(self, chain, action='accept', **kwargs):
        """Add a rule to a chain."""
        # Set default values
        prop_defaults = {
            "nw_src_addr": None,
            "nw_src_length": None,
            "inv_nw_src": False,
            "tp_src": None,
            "inv_tp_src": None,
            "nw_dst_addr": None,
            "nw_dst_length": None,
            "inv_nw_dst_addr": False,
            "tp_dst": None,
            "inv_tp_dst": None,
            "dl_src": None,
            "inv_dl_src": False,
            "dl_dst": None,
            "inv_dl_dst": False,
            "ip_addr_group_src": None,
            "inv_ip_addr_group_src": False,
            "ip_addr_group_dst": None,
            "inv_ip_addr_group_dst": False,
            "nw_proto": None,
            "inv_nw_proto": False,
            "dl_type": None,
            "inv_dl_type": False,
            "jump_chain_id": None,
            "jump_chain_name": None,
            "match_forward_flow": False,
            "match_return_flow": False,
            "position": None,
            "properties": None
        }

        # Initialize the rule with passed-in or default values
        vals = {}
        for (prop, default) in prop_defaults.iteritems():
            vals[prop] = kwargs.get(prop, default)

        rule = chain.add_rule().type(action)
        rule = rule.nw_src_address(vals.get("nw_src_addr"))
        rule = rule.nw_src_length(vals.get("nw_src_length"))
        rule = rule.inv_nw_src(vals.get("inv_nw_src"))
        rule = rule.nw_dst_address(vals.get("nw_dst_addr"))
        rule = rule.nw_dst_length(vals.get("nw_dst_length"))
        rule = rule.inv_nw_dst(vals.get("inv_nw_dst"))
        rule = rule.tp_src(vals.get("tp_src"))
        rule = rule.inv_tp_src(vals.get("inv_tp_src"))
        rule = rule.tp_dst(vals.get("tp_dst"))
        rule = rule.inv_tp_dst(vals.get("inv_tp_dst"))
        rule = rule.dl_src(vals.get("dl_src"))
        rule = rule.inv_dl_src(vals.get("inv_dl_src"))
        rule = rule.dl_dst(vals.get("dl_dst"))
        rule = rule.inv_dl_dst(vals.get("inv_dl_dst"))
        rule = rule.ip_addr_group_src(vals.get("ip_addr_group_src"))
        rule = rule.inv_ip_addr_group_src(vals.get("inv_ip_addr_group_src"))
        rule = rule.ip_addr_group_dst(vals.get("ip_addr_group_dst"))
        rule = rule.inv_ip_addr_group_dst(vals.get("inv_ip_addr_group_dst"))
        rule = rule.nw_proto(vals.get("nw_proto"))
        rule = rule.inv_nw_proto(vals.get("inv_nw_proto"))
        rule = rule.dl_type(vals.get("dl_type"))
        rule = rule.inv_dl_type(vals.get("inv_dl_type"))
        rule = rule.jump_chain_id(vals.get("jump_chain_id"))
        rule = rule.jump_chain_name(vals.get("jump_chain_name"))
        rule = rule.match_forward_flow(vals.get("match_forward_flow"))
        rule = rule.match_return_flow(vals.get("match_return_flow"))
        rule = rule.position(vals.get("position"))
        rule = rule.properties(vals.get("properties"))
        return rule.create()

    def get_vteps(self):
        self._ensure_application()
        return self.app.get_vteps()

    def add_vtep(self):
        self._ensure_application()
        return self.app.add_vtep()

    def get_vtep(self, id):
        self._ensure_application()
        return self.app.get_vtep(id)

    def delete_vtep(self, id):
        self._ensure_application()
        return self.app.delete_vtep(id)

    def get_tracerequests(self, query):
        self._ensure_application()
        return self.app.get_tracerequests(query)

    def add_tracerequest(self):
        self._ensure_application()
        return self.app.add_tracerequest()

    def get_tracerequest(self, _id):
        self._ensure_application()
        return self.app.get_tracerequest(_id)

    def delete_tracerequest(self, _id):
        self._ensure_application()
        return self.app.delete_tracerequest(_id)

    def _ensure_application(self):
        if self.app is None:
            self.app = application.Application(None, {'uri': self.base_uri},
                                               self.auth)
            try:
                self.app.get()
            except exc.MidoApiConnectionRefused:
                self.app = None
                raise

# just for testing
if __name__ == '__main__':

    import sys
    import uuid

    if len(sys.argv) < 4:
        print >> sys.stderr, "Functional testing with MN API"
        print >> sys.stderr, "Usage: " + sys.argv[0] \
            + " <URI> <username> <password> [project_id]"
        sys.exit(-1)

    uri = sys.argv[1]
    username = sys.argv[2]
    password = sys.argv[3]
    if len(sys.argv) > 4:
        project_id = sys.argv[4]
    else:
        project_id = None

    tenant_id = str(uuid.uuid4())
    FORMAT = '%(asctime)-15s %(name)s %(message)s'
    logging.basicConfig(format=FORMAT)
    LOG.setLevel(logging.DEBUG)

    api = MidonetApi(uri, username, password, project_id=project_id)

    # Tunnel zones
    tz1 = api.add_gre_tunnel_zone().name('tunnel_vision').create()
    tz1.name("going' through my head").update()
    tz1.get_hosts()
    tz1.delete()

    tz2 = api.add_capwap_tunnel_zone().name('tunnel_vision2').create()
    tz2.name("going' through my head2").update()
    tz2.get_hosts()
    tz2.delete()

    # Routers
    api.get_routers({'tenant_id': 'non-existent'})
    print api.get_routers({'tenant_id': tenant_id})

    router1 = api.add_router().name('router-1').tenant_id(tenant_id).create()
    api.get_routers({'tenant_id': 'non-existent'})
    api.get_router(router1.get_id())

    router2 = api.add_router().name('router-2').tenant_id(tenant_id).create()

    router1.name('router1-changed').update()

    api.get_router(router1.get_id())

    for r in api.get_routers({'tenant_id': tenant_id}):
        print '--------', r.get_name()
        print 'id: ', r.get_id()
        print 'inboundFilterId: ', r.get_inbound_filter_id()
        print 'outboundFilterId: ', r.get_outbound_filter_id()

    api.get_router(router1.get_id())

    print '-------- Tenants ------'
    tenants = api.get_tenants(query={})
    for t in tenants:
        print 'id: ', t.get_id()
        print 'name: ', t.get_name()

        print '----- Tenant by ID ------'
        t = api.get_tenant(t.get_id())
        print 'id: ', t.get_id()
        print 'name: ', t.get_name()

    # Tenant routers
    print '-------- Tenant Routers ------'
    for t in tenants:
        for r in t.get_routers():
            print 'id: ', r.get_id()

    # Routers/Ports

    rp1 = router1.add_port()\
                 .port_address('2.2.2.2')\
                 .network_address('2.2.2.0')\
                 .network_length(24).create()

    rp2 = router1.add_port().port_address('1.1.1.1')\
                 .network_address('1.1.1.0').network_length(24).create()

    rp3 = router1.add_port().port_address('1.1.1.2')\
                 .network_address('1.1.1.0').network_length(24).create()
    rp4 = router1.add_port().port_address('1.1.1.3')\
                 .network_address('1.1.1.0').network_length(24).create()
    print api.get_port(rp1.get_id())

    # Router/Routes

    print '-------- router/routes'
    router1.add_route().type('Normal').src_network_addr('0.0.0.0')\
                       .src_network_length(0)\
                       .dst_network_addr('100.100.100.1')\
                       .dst_network_length(32)\
                       .weight(1000)\
                       .next_hop_port(rp4.get_id())\
                       .next_hop_gateway('10.0.0.1').create()

    print router1.get_routes()

    rp2.link(rp3.get_id())

    # Remove all the existing IP addr groups and their addresses
    ip_addr_groups = api.get_ip_addr_groups()
    print "Removing %d IP addr groups" % len(ip_addr_groups)
    for ip_addr_group in ip_addr_groups:
        # Get all the addresses
        addrs = ip_addr_group.get_addrs()
        print "Removing %d IP addrs" % len(addrs)
        for addr in addrs:
            print "Removing %r" % addr
            addr.delete()
        print "Removing %r" % ip_addr_group
        ip_addr_group.delete()

    # IP Addr group
    ip_addr_group = api.add_ip_addr_group().name("foo").create()

    # Get itn
    ip_addr_groups = api.get_ip_addr_groups()
    print "Got %d IP addr groups" % len(ip_addr_groups)
    assert 1 == len(ip_addr_groups)

    # Add IPv4 address
    ip_addr_group_addr = ip_addr_group.add_ipv4_addr().addr(
        "10.0.10.1").create()

    # Get the addresses
    ip_addr_group_addrs = ip_addr_group.get_addrs()
    assert 1 == len(ip_addr_group_addrs)

    # Delete the address
    ip_addr_group_addrs[0].delete()

    # Make sure it's gone
    ip_addr_group_addrs = ip_addr_group.get_addrs()
    assert 0 == len(ip_addr_group_addrs)

    # Delete it
    ip_addr_group.delete()

    # Get it again
    ip_addr_groups = api.get_ip_addr_groups()
    print "Got %d IP addr groups" % len(ip_addr_groups)
    assert 0 == len(ip_addr_groups)

    # Bridges
    bridge1 = api.add_bridge().name('bridge-1').tenant_id(tenant_id).create()
    bridge1.name('bridge1-changed').update()

    bridge2 = api.add_bridge().name('bridge-2').tenant_id(tenant_id).create()

    for b in api.get_bridges({'tenant_id': tenant_id}):
        print '--------', b.get_name()
        print 'id: ', b.get_id()
        print 'inboundFilterId: ', b.get_inbound_filter_id()
        print 'outboundFilterId: ', b.get_outbound_filter_id()

    print api.get_bridge(bridge1.get_id())

    # Tenant bridges
    print '-------- Tenant Bridges ------'
    for t in tenants:
        for b in t.get_bridges():
            print 'id: ', b.get_id()

    # Bridges/Ports
    bp1 = bridge1.add_port().create()

    bp2 = bridge1.add_port().create()

    print api.get_port(bp1.get_id())
    bp2.link(rp4.get_id())

    print router1.get_peer_ports({})
    print bridge1.get_peer_ports({})

    for bp in bridge1.get_ports():
        print 'bridge port----'
        print bp.get_id()

    dhcp1 = bridge1.add_dhcp_subnet().default_gateway('10.10.10.1')\
                   .subnet_prefix('10.10.10.0').subnet_length(24).create()

    dhcp2 = bridge1.add_dhcp_subnet().default_gateway(
        '11.11.11.1').subnet_prefix('11.11.11.0').subnet_length(24).create()

    dhcp1.add_dhcp_host().name('host-1').ip_addr('10.10.10.2')\
                         .mac_addr('00:00:00:aa:bb:cc').create()
    dhcp1.add_dhcp_host().name('host-2').ip_addr('10.10.10.3')\
                         .mac_addr('00:00:00:aa:bb:dd').create()

    assert 2 == len(dhcp1.get_dhcp_hosts())

    for ds in bridge1.get_dhcp_subnets():
        print 'dhcp subnet', ds

    bridge1.get_dhcp_subnet('11.11.11.0_24')

    # tear down routers and bridges
    bp2.unlink()    # if I don't unlink, deletion of router blows up
    rp2.unlink()

    router1.delete()
    router2.delete()
    bridge1.delete()
    bridge2.delete()

    # Chains
    chain1 = api.add_chain().tenant_id(tenant_id).name('chain-1').create()
    chain2 = api.add_chain().tenant_id(tenant_id).name('chain-2').create()

    for c in api.get_chains({'tenant_id': tenant_id}):
        print '------- chain: ', c.get_name()
        print c.get_id()

    # Tenant chains
    print '-------- Tenant Chains -----'
    for t in tenants:
        for c in t.get_chains():
            print 'id: ', c.get_id()

    rule1 = chain1.add_rule().type('accept').create()
    rule2 = chain1.add_rule().type('reject').create()

    nat_targets = [{'addressFrom': '192.168.100.1',
                    'addressTo': '192.168.100.10',
                    'portFrom': 80,
                    'portTo': 80},
                   {'addressFrom': '192.168.100.20',
                    'addressTo': '192.168.100.30',
                    'portFrom': 80,
                    'portTo': 80}]

    rule3 = chain1.add_rule().type('dnat').nw_dst_address('1.1.1.1')\
                             .nw_dst_length(32)\
                             .flow_action('accept')\
                             .nat_targets(nat_targets)\
                             .create()

    print '=' * 10
    print rule3.get_nat_targets()

    chain1.delete()
    chain2.delete()
