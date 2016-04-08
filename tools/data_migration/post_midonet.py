#!/usr/bin/env python
# Copyright (C) 2016 Midokura SARL
# All Rights Reserved.
#
#    Licensed under the Apache License, Version 2.0 (the "License"); you may
#    not use this file except in compliance with the License. You may obtain
#    a copy of the License at
#
#         http://www.apache.org/licenses/LICENSE-2.0
#
#    Unless required by applicable law or agreed to in writing, software
#    distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
#    WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
#    License for the specific language governing permissions and limitations
#    under the License.


from data_migration import migration_config as cfg
from data_migration import migration_defs
from data_migration import migration_funcs

import json
import logging

from oslo_config import cfg as cmd_arg

from webob.exc import HTTPClientError


log = logging.getLogger(name='data_migration')
""":type: logging.Logger"""

parser_opts = [
    cmd_arg.BoolOpt(
        'dryrun', short='n', default=False, dest='dryrun',
        help='Perform a "dry run" and print out the examined '
             'information and actions that would normally be '
             'taken, before exiting.'),
    cmd_arg.BoolOpt(
        'debug', short='d', default=False, dest='debug',
        help='Turn on debug logging (off by default).'),
]

cli_conf = cmd_arg.ConfigOpts()
cli_conf.register_cli_opts(parser_opts)

cli_conf()

debug = cli_conf['debug']
dry_run = cli_conf['dryrun']

mc = cfg.MigrationConfig()


def _create_mn_tz_hosts_uplink_router(mn_map, new_id_map):
    for tz in mn_map['tunnel_zones'] if 'tunnel_zones' in mn_map else []:
        tz_obj = tz['tz']
        if dry_run:
            log.info("cfg.mn_api.add_tunnel_zone()"
                     ".type(" + tz_obj['type'] + ")"
                     ".name(" + tz_obj['name'] + ")"
                     ".create()")
            new_tz = None
        else:
            try:
                new_tz = (mc.mn_api.add_tunnel_zone()
                          .type(tz_obj['type'])
                          .name(tz_obj['name'])
                          .create())
                new_id_map[new_tz.get_id()] = new_tz
            except HTTPClientError as e:
                if e.code == 409:
                    log.warn('Tunnel zone already exists: ' + tz_obj['name'])
                    tz_list = mc.mn_api.get_tunnel_zones()
                    new_tz = next(tz
                                  for tz in tz_list
                                  if tz.get_name() == tz_obj['name'])
                else:
                    raise e

        """ :type: midonetclient.tunnel_zone.TunnelZone"""

        for host_id, host in iter(tz['hosts'].items()):
            if dry_run:
                log.info("new_tz.add_tunnel_zone_host()"
                         ".ip_address(" + host['ipAddress'] + ")"
                         ".host_id(" + host['hostId'] + ")"
                         ".create()")
            else:
                new_host = (new_tz.add_tunnel_zone_host()
                            .ip_address(host['ipAddress'])
                            .host_id(host['hostId'])
                            .create())
                new_id_map[new_host.get_id()] = new_host

    for host_id, host in (iter(mn_map['hosts'].items())
                          if 'hosts' in mn_map else []):
        host_obj = mc.mn_api.get_host(host_id)
        for iface, port in iter(host['ports'].items()):
            if dry_run:
                log.info("cfg.mn_api.add_host_interface_port("
                         "host_obj,"
                         "port_id=" + port + ","
                                             "interface_name=" + iface + ")")
            else:
                mc.mn_api.add_host_interface_port(
                    host_obj,
                    port_id=port,
                    interface_name=iface)


def _mn_get_bridge(_, bridge_id):
    return mc.mn_api.get_bridge(bridge_id)


def _mn_create_bridge(_, bridge):
    return (mc.mn_api.add_bridge()
            .name(bridge['name'])
            .inbound_filter_id(bridge['inboundFilterId'])
            .outbound_filter_id(bridge['outboundFilterId'])
            .create())


def _mn_get_dhcp(bridge, cidr):
    return bridge.get_dhcp_subnet(cidr.replace('/', '_'))


def _mn_create_dhcp_subnet(bridge, dhcp):
    return (bridge.add_dhcp_subnet()
            .default_gateway(dhcp['defaultGateway'])
            .server_addr(dhcp['serverAddr'])
            .dns_server_addrs(dhcp['dnsServerAddrs'])
            .subnet_prefix(dhcp['subnetPrefix'])
            .subnet_length(dhcp['subnetLength'])
            .interface_mtu(dhcp['interfaceMTU'])
            .opt121_routes(dhcp['opt121Routes'])
            .enabled(True)
            .create())


def _mn_create_dhcp_host(subnet, dhcp_host):
    return (subnet.add_dhcp_host()
            .name(dhcp_host['name'])
            .ip_addr(dhcp_host['ipAddr'])
            .mac_addr(dhcp_host['macAddr'])
            .create())


def _mn_create_bridge_port(bridge, port):
    return (bridge.add_port().
            vlan_id(port['vlanId'])
            .create())


def _mn_get_router(_, router_id):
    return mc.mn_api.get_router(router_id)


def _mn_create_router(_, router):
    return (mc.mn_api.add_router()
            .name(router['name'])
            .inbound_filter_id(router['inboundFilterId'])
            .outbound_filter_id(router['outboundFilterId'])
            .create())


def _mn_create_route(router, route):
    return (router.add_route()
            .attributes(route['attributes'])
            .learned(route['learned'])
            .dst_network_addr(route['dstNetworkAddr'])
            .dst_network_length(route['dstNetworkLength'])
            .src_network_addr(route['srcNetworkAddr'])
            .src_network_length(route['srcNetworkLength'])
            .next_hop_gateway(route['nextHopGateway'])
            .next_hop_port(route['nextHopPort'])
            .type(route['type'])
            .weight(route['weight'])
            .create())


def _mn_create_router_port(router, port):
    return (router.add_port()
            .port_address(port['portAddress'])
            .network_address(port['networkAddress'])
            .network_length(port['networkLength'])
            .port_mac(port['portMac'])
            .create())


def _mn_create_router_bgp_net(router, ad_route):
    return (router.add_bgp_network()
            .subnet_address(ad_route['nwPrefix'])
            .subnet_length(ad_route['prefixLength'])
            .create())


def _mn_create_router_bgp_peer(router, peer):
    return (router.add_bgp_peer()
            .asn(peer['peerAS'])
            .address(peer['peerAddr'])
            .create())


def _mn_get_chain(_, chain_id):
    return mc.mn_api.get_chain(chain_id)


def _mn_create_chain(_, chain):
    return (mc.mn_api.add_chain()
            .name(chain['name'])
            .create())


def _mn_create_rule(chain, rule):
    """
    :type chain: midonetclient.chain.Chain
    :return:
    """
    return (chain.add_rule().properties(rule["properties"])
            .position(rule["position"])
            .inv_tp_dst(rule["invTpDst"])
            .inv_dl_src(rule["invTpSrc"])
            .tp_dst(rule["tpDst"])
            .tp_src(rule["tpSrc"])
            .inv_ip_addr_group_src(rule["invIpAddrGroupSrc"])
            .ip_addr_group_src(rule["ipAddrGroupSrc"])
            .inv_port_group(rule["invPortGroup"])
            .port_group(rule["portGroup"])
            .inv_out_ports(rule["invOutPorts"])
            .out_ports(["outPorts"])
            .inv_in_ports(rule["invInPorts"])
            .in_ports(rule["inPorts"])
            .nw_dst_address(rule["nwDstAddress"])
            .create())


def _mn_get_load_balancer(_, lb_id):
    return mc.mn_api.get_load_balancer(lb_id)


def _mn_create_load_balancer(router, lb):
    lb_obj = (mc.mn_api.add_load_balancer()
              .id(lb['id'])
              .admin_state_up(lb['adminStateUp'])
              .create())
    router.load_balancer_id(lb_obj.get_id()).update()
    return lb_obj


def _mn_get_pool(_, pool_id):
    return mc.mn_api.get_pool(pool_id)


def _mn_create_pool(lb, pool):
    return (lb.add_pool()
            .load_balancer_id(lb.get_id())
            .lb_method(pool['lbMethod'])
            .health_monitor_id(pool['healthMonitorId'])
            .protocol(pool['protocol'])
            .create())


def _mn_create_member(pool, member):
    return (pool
            .add_pool_member()
            .pool_id(member['poolId'])
            .address(member['address'])
            .protocol_port(member['protocolPort'])
            .weight(member['weight'])
            .status(member['status'])
            .create())


def _mn_create_vip(pool, vip):
    return (pool.add_vip()
            .load_balancer_id(pool.get_load_balancer_id())
            .pool_id(vip['poolId'])
            .address(vip['address'])
            .protocol_port(vip['protocolPort'])
            .session_persistence(vip['sessionPersistence'])
            .create())


def _mn_get_port_group(_, pg_id):
    return mc.mn_api.get_port_group(pg_id)


def _mn_create_port_group(pg):
    return (mc.mn_api.add_port_group()
            .name(pg['name'])
            .stateful(pg['stateful'])
            .tenant_id(pg['tenantId'])
            .create())


def _mn_create_port_group_port(pg, port):
    return (pg
            .add_port_group_port()
            .portId(port['portId'])
            .create())


def _get_object_to_update_create(old_obj_id, obj, obj_type,
                                 new_id_map,
                                 root_parent=None,
                                 get_func=None, create_func=None,
                                 indent=0,
                                 update_id_field=True):
    operation = obj['operation']
    ret_obj = None
    if operation == 'update':
        log.info((" "*indent) + "Updating (MN) " +
                 obj_type + "(" + old_obj_id + ")")
        if not dry_run and get_func:
            ret_obj = get_func(root_parent, old_obj_id)
    elif operation == 'create':
        log.info((" "*indent) + "ADD (MN) " +
                 obj_type + "(" + old_obj_id + ")")
        if not dry_run and create_func:
            ret_obj = create_func(root_parent, obj)

    if update_id_field:
        log.info((" "*(indent*2)) + "MAP old (MN) " +
                 obj_type + "(" + old_obj_id +
                 ") ID to: " + ret_obj.get_id())
        new_id_map[old_obj_id] = ret_obj.get_id()
    return ret_obj


def _run_midonet_post():
    with open(migration_defs.MIDONET_POST_COMMAND_FILE) as f:
        log.debug('Post-processing midonet topology with data in: ' +
                  migration_defs.MIDONET_POST_COMMAND_FILE)
        mn_map = json.load(f)

    new_id_map = {}
    created_port_list = []
    bgp_info_map = {}

    for bridge_id, bridge in (iter(mn_map['bridges'].items())
                              if 'bridges' in mn_map else []):
        bridge_obj = _get_object_to_update_create(
            bridge_id, bridge, "bridge",
            new_id_map,
            get_func=_mn_get_bridge, create_func=_mn_create_bridge)

        for cidr, dhcp in (iter(bridge['dhcpSubnets'].items())
                           if 'dhcpSubnets' in bridge else []):
            dhcp_obj = _get_object_to_update_create(
                cidr, dhcp, "dhcp-subnet",
                new_id_map,
                root_parent=bridge_obj,
                get_func=_mn_get_dhcp, create_func=_mn_create_dhcp_subnet,
                indent=4,
                update_id_field=False)

            for host_id, dhcp_host in (iter(dhcp['hosts'].items())
                                       if 'hosts' in dhcp else []):
                _get_object_to_update_create(
                    host_id, dhcp_host, "dhcp-host",
                    new_id_map,
                    root_parent=dhcp_obj,
                    create_func=_mn_create_dhcp_subnet,
                    indent=8,
                    update_id_field=False)

        for port_id, port in (iter(bridge['ports'].items())
                              if 'ports' in bridge else []):
            port_obj = _get_object_to_update_create(
                port_id, port, "bridge-port",
                new_id_map,
                root_parent=bridge_obj,
                create_func=_mn_create_bridge_port,
                indent=4)

            created_port_list.append((port_id, port, port_obj))

    for router_id, router in (iter(mn_map['routers'].items())
                              if 'routers' in mn_map else []):
        router_obj = _get_object_to_update_create(
            router_id, router, "router",
            new_id_map,
            get_func=_mn_get_router, create_func=_mn_create_router)

        bgp_info_map[router_id] = []

        for route_id, route in (iter(router['routes'].items())
                                if 'routes' in mn_map else []):
            _get_object_to_update_create(
                route_id, route, "route",
                new_id_map,
                root_parent=router_obj,
                create_func=_mn_create_route,
                indent=4)

        for port_id, port in (iter(router['ports'].items())
                              if 'ports' in router else []):
            port_obj = _get_object_to_update_create(
                port_id, port, "router-port",
                new_id_map,
                root_parent=router_obj,
                create_func=_mn_create_router_port,
                indent=4)

            # We need to handle BGP.  In 1.9, this was on the
            # port, but in 5.0, this is now on the router
            if 'bgps' in port:
                for bgp_id, bgp in iter(port['bgps'].items()):
                    bgp_info_map[router_id].append(bgp)

            created_port_list.append((port_id, port, port_obj))

    for chain_id, chain in (iter(mn_map['chains'].items())
                            if 'chains' in mn_map else []):
        _get_object_to_update_create(
            chain_id, chain, "chain",
            new_id_map,
            get_func=_mn_get_chain, create_func=_mn_create_chain)

        for cr_id, cr in (iter(chain['rules'].items())
                          if 'rules' in chain else []):
            _get_object_to_update_create(
                cr_id, cr, "chain-rule",
                new_id_map,
                create_func=_mn_create_rule,
                indent=4)

    for lb_id, lb in (iter(mn_map['load_balancers'].items())
                      if 'load_balancers' in mn_map else []):
        old_router_id = lb['routerId']
        if old_router_id not in new_id_map:
            raise migration_funcs.UpgradeScriptException(
                "Load Balancer router ID: " + old_router_id + " doesn't have "
                "a new ID.  Has it been created yet?")

        router_obj = mc.mn_api.get_router(new_id_map[old_router_id])

        lb_obj = _get_object_to_update_create(
            lb_id, lb, "load-balancer",
            new_id_map,
            root_parent=router_obj,
            get_func=_mn_get_load_balancer,
            create_func=_mn_create_load_balancer)

        for pool_id, pool in (iter(lb['pools'].items())
                              if 'pools' in lb else []):
            pool_obj = _get_object_to_update_create(
                pool_id, pool, "pool",
                new_id_map,
                root_parent=lb_obj,
                get_func=_mn_get_pool, create_func=_mn_create_pool)

            for member_id, member in iter(pool['members'].items()):
                _get_object_to_update_create(
                    member_id, member, "pool-member",
                    new_id_map,
                    root_parent=pool_obj,
                    create_func=_mn_create_member,
                    indent=4)

            for vid, vip in iter(pool['vips'].items()):
                _get_object_to_update_create(
                    vid, vip, "vip",
                    new_id_map,
                    root_parent=pool_obj,
                    create_func=_mn_create_vip,
                    indent=4)

    for pg_id, pg in (iter(mn_map['port_groups'].items())
                      if 'port_groups' in mn_map else []):
        pg_obj = _get_object_to_update_create(
            pg_id, pg, "port-group",
            new_id_map,
            get_func=_mn_get_port_group, create_func=_mn_create_port_group)

        for port_id, port in (iter(pg['ports'].items())
                              if 'ports' in pg else []):
            _get_object_to_update_create(
                port_id, port, "port-group-port",
                new_id_map,
                root_parent=pg_obj,
                create_func=_mn_create_port_group_port,
                indent=4)

    # For ports, we need to link peers
    peered_ports = set()
    for port_id, port, port_obj in created_port_list:
        new_port_id = new_id_map[port_id]
        if port['peerId'] not in new_id_map:
            raise migration_funcs.UpgradeScriptException(
                "Port with old ID: " + port_id + " and new ID: " +
                new_port_id + " tried to link to old peer ID: " +
                port['peerId'] + ", but did not find a new peer ID.  "
                "Has the peer port been created yet?")
        new_peer_id = port['peerId']
        if new_peer_id not in peered_ports:
            # Check to make sure we haven't already peered this from
            # the other side!
            if dry_run:
                log.debug("Would link ports: " + new_port_id + " <-> " +
                          new_peer_id)
            else:
                port_obj.link(new_peer_id)
        peered_ports.add(new_port_id)

    _create_mn_tz_hosts_uplink_router(mn_map, new_id_map)

    # BGP
    uplink_router = next(
        r
        for r in mc.mn_api.get_routers()
        if r.get_name() == migration_defs.UPLINK_ROUTER_NAME)
    ur_id = uplink_router.get_id()
    uplink_bgp = mn_map['uplink_router_bgp']
    bgp_info_map[ur_id] = {}
    for port_id, bgp_list in iter(uplink_bgp.items()):
        for bgp_id, bgp in bgp_list:
            bgp_info_map[ur_id].append(bgp)

    for router_id, bgp_list in iter(bgp_info_map.items()):
        bgp_net_map = {}
        bgp_peer_map = {}
        router_obj = mc.mn_api.get_router(router_id)
        for bgp in bgp_list:
            for ad_route in (iter(bgp['adRoutes'].items())
                             if 'adRoutes' in bgp else {}):
                cidr = (ad_route['nwPrefix'] + '/' +
                        str(ad_route['prefixLength']))
                bgp_net_map[cidr] = ad_route
            bgp_peer_unique_id = bgp['peerAS'] + '/' + bgp['peerAddr']
            bgp_peer_map[bgp_peer_unique_id] = bgp

        for cidr, ad_route in bgp_net_map:
            log.info("BGP (MN) " + router_id + " ADD NET (" +
                     str(ad_route) + ")")
            if not dry_run:
                _mn_create_router_bgp_net(router_obj, ad_route)

        for peer_id, peer in bgp_peer_map:
            log.info("BGP (MN) " + router_id + " ADD PEER (" +
                     str(peer) + ")")
            if not dry_run:
                _mn_create_router_bgp_peer(router_obj, peer)


log.setLevel(level=logging.DEBUG if debug else logging.INFO)

log.info("Running post-migration configuration")
_run_midonet_post()
