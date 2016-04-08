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

import os
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
    cmd_arg.BoolOpt(
        'midonet-only', short='m', default=False, dest='midonet_only',
        help='Only run midonet migration tasks.'),
    cmd_arg.BoolOpt(
        'neutron-only', short='t', default=False, dest='neutron_only',
        help='Only run neutron migration tasks.'),
]

cli_conf = cmd_arg.ConfigOpts()
cli_conf.register_cli_opts(parser_opts)

cli_conf()

debug = cli_conf['debug']
dry_run = cli_conf['dryrun']
midonet_only = cli_conf['midonet_only']
neutron_only = cli_conf['neutron_only']

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

        for host_id, host in tz['hosts'].iteritems():
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

    for host_id, host in (mn_map['hosts'].iteritems()
                          if 'hosts' in mn_map else []):
        host_obj = mc.mn_api.get_host(host_id)
        for iface, port in host['ports'].iteritems():
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

    _create_mn_tz_hosts_uplink_router(mn_map, new_id_map)

    for bridge_id, bridge in (mn_map['bridges'].iteritems()
                              if 'bridges' in mn_map else []):
        bridge_obj = _get_object_to_update_create(
            bridge_id, bridge, "bridge",
            new_id_map,
            get_func=_mn_get_bridge, create_func=_mn_create_bridge)

        for cidr, dhcp in (bridge['dhcpSubnets'].iteritems()
                           if 'dhcpSubnets' in bridge else []):
            dhcp_obj = _get_object_to_update_create(
                cidr, dhcp, "dhcp-subnet",
                new_id_map,
                root_parent=bridge_obj,
                get_func=_mn_get_dhcp, create_func=_mn_create_dhcp_subnet,
                indent=4,
                update_id_field=False)

            for host_id, dhcp_host in (dhcp['hosts'].iteritems()
                                       if 'hosts' in dhcp else []):
                _get_object_to_update_create(
                    host_id, dhcp_host, "dhcp-host",
                    new_id_map,
                    root_parent=dhcp_obj,
                    create_func=_mn_create_dhcp_subnet,
                    indent=8,
                    update_id_field=False)

        for port_id, port in (bridge['ports'].iteritems()
                              if 'ports' in bridge else []):
            port_obj = _get_object_to_update_create(
                port_id, port, "bridge-port",
                new_id_map,
                root_parent=bridge_obj,
                create_func=_mn_create_bridge_port,
                indent=4)
            created_port_list.append((port_id, port, port_obj))

    for router_id, router in (mn_map['routers'].iteritems()
                              if 'routers' in mn_map else []):
        router_obj = _get_object_to_update_create(
            router_id, router, "router",
            new_id_map,
            get_func=_mn_get_router, create_func=_mn_create_router)

        for route_id, route in (router['routes'].iteritems()
                                if 'routes' in mn_map else []):
            _get_object_to_update_create(
                route_id, route, "route",
                new_id_map,
                root_parent=router_obj,
                create_func=_mn_create_route,
                indent=4)

        for port_id, port in (router['ports'].iteritems()
                              if 'ports' in router else []):
            port_obj = _get_object_to_update_create(
                port_id, port, "router-port",
                new_id_map,
                root_parent=router_obj,
                create_func=_mn_create_router_port,
                indent=4)
            created_port_list.append((port_id, port, port_obj))
        # For ports, we need to link peers

    for chain_id, chain in (mn_map['chains'].iteritems()
                            if 'chains' in mn_map else []):
        _get_object_to_update_create(
            chain_id, chain, "chain",
            new_id_map,
            get_func=_mn_get_chain, create_func=_mn_create_chain)

        for cr_id, cr in (chain['rules'].iteritems()
                          if 'rules' in chain else []):
            _get_object_to_update_create(
                cr_id, cr, "chain-rule",
                new_id_map,
                create_func=_mn_create_rule,
                indent=4)

    for lb_id, lb in (mn_map['load_balancers'].iteritems()
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

        for pool_id, pool in (lb['pools'].iteritems()
                              if 'pools' in lb else []):
            pool_obj = _get_object_to_update_create(
                pool_id, pool, "pool",
                new_id_map,
                root_parent=lb_obj,
                get_func=_mn_get_pool, create_func=_mn_create_pool)

            for member_id, member in pool['members'].iteritems():
                _get_object_to_update_create(
                    member_id, member, "pool-member",
                    new_id_map,
                    root_parent=pool_obj,
                    create_func=_mn_create_member,
                    indent=4)

            for vid, vip in pool['vips'].iteritems():
                _get_object_to_update_create(
                    vid, vip, "vip",
                    new_id_map,
                    root_parent=pool_obj,
                    create_func=_mn_create_vip,
                    indent=4)

    for pg_id, pg in (mn_map['port_groups'].iteritems()
                      if 'port_groups' in mn_map else []):
        pg_obj = _get_object_to_update_create(
            pg_id, pg, "port-group",
            new_id_map,
            get_func=_mn_get_port_group, create_func=_mn_create_port_group)

        for port_id, port in (pg['ports'].iteritems()
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
                "Has the peer port been created yet?"
            )
        new_peer_id = port['peerId']
        if new_peer_id not in peered_ports:
            # Check to make sure we haven't already peered this from
            # the other side!
            port_obj.link(new_peer_id)
        peered_ports.add(new_port_id)


def _run_neutron_post():
    with open(migration_defs.NEUTRON_POST_COMMAND_FILE) as f:
        log.debug('Post-processing neutron topology with data in: ' +
                  migration_defs.NEUTRON_POST_COMMAND_FILE)
        nt_map = json.load(f)

    if 'uplink_router' in nt_map:
        ur = nt_map['uplink_router']
        ext_ports = ur['uplink_ports'] if 'uplink_ports' in ur else []
        ext_subnets = ur['ext_subnets'] if 'ext_subnets' in ur else []
        routes = ur['routes'] if 'routes' in ur else []

        router_obj = {'router': {'name': ur['name'],
                                 'tenant_id': 'admin',
                                 'admin_state_up': True}}
        if dry_run:
            upl_router = None
            log.info('Would run create_router with: ' + str(router_obj))
        else:
            upl_router = mc.client.create_router(
                mc.ctx,
                router_obj)
            log.debug('Created router: ' + str(upl_router))

        for port in ext_ports:
            base_name = port['host'] + "_" + port['iface']
            net_obj = {'network': {'name': base_name + "_uplink_net",
                                   'tenant_id': 'admin',
                                   'shared': False,
                                   'provider:network_type': 'uplink',
                                   'admin_state_up': True}}
            if dry_run:
                upl_net = {'id': 'uplink_net_id'}
                log.info('Would run create_network with: ' + str(router_obj))
            else:
                upl_net = mc.client.create_network(
                    mc.ctx,
                    net_obj)
                log.debug('Created network: ' + str(upl_net))

            subnet_obj = {'subnet': {'name': base_name + "_uplink_subnet",
                                     'network_id': upl_net['id'],
                                     'ip_version': 4,
                                     'cidr': port['network_cidr'],
                                     'dns_nameservers': [],
                                     'host_routes': [],
                                     'allocation_pools': None,
                                     'enable_dhcp': False,
                                     'tenant_id': 'admin',
                                     'admin_state_up': True}}
            if dry_run:
                upl_sub = {'id': 'uplink_subnet_id'}
                log.info('Would run create_subnet with: ' + str(subnet_obj))
            else:
                upl_sub = mc.client.create_subnet(
                    mc.ctx,
                    subnet_obj)
            log.debug('Created subnet: ' + str(upl_sub))

            port_obj = {'port': {'name': base_name + "_uplink_port",
                                 'tenant_id': 'admin',
                                 'network_id': upl_net['id'],
                                 'device_id': '',
                                 'device_owner': '',
                                 'mac_address': port['mac'],
                                 'fixed_ips': [{'subnet_id': upl_sub['id'],
                                                'ip_address': port['ip'],
                                                }],
                                 'binding:host_id': port['host'],
                                 'binding:profile': {
                                     'interface_name': port['iface']},
                                 'admin_state_up': True}}
            if dry_run:
                bound_port = {'id': 'uplink_port_id'}
                log.info('Would run create_port with: ' + str(port_obj))
            else:
                bound_port = mc.client.create_port(
                    mc.ctx,
                    port_obj)
            log.debug('Created port: ' + str(bound_port))

            iface_obj = {'port_id': bound_port['id']}
            if dry_run:
                log.info('Would run add_router_interface with: ' +
                         str(iface_obj))
            else:
                iface = mc.client.add_router_interface(
                    mc.ctx,
                    upl_router['id'],
                    iface_obj)
                log.debug('Added interface: ' + str(iface))

        for subnet in ext_subnets:
            iface_obj = {'subnet_id': subnet}
            if dry_run:
                log.info('Would run add_router_interface with: ' +
                         str(iface_obj))
            else:
                iface = mc.client.add_router_interface(
                    mc.ctx,
                    upl_router['id'],
                    iface_obj)
                log.debug('Added ext-net interface: ' + str(iface))

        if len(routes) > 0:
            route_obj = {'router': {'routes': routes}}
            if dry_run:
                log.info('Would run update_router with: ' + str(route_obj))
            else:
                new_router = mc.client.update_router(
                    mc.ctx,
                    upl_router['id'],
                    route_obj)
                log.debug('Updated router to: ' + str(new_router))


log.setLevel(level=logging.DEBUG if debug else logging.INFO)

log.info("Running post-migration configuration")

if os.path.isfile(migration_defs.MIDONET_POST_COMMAND_FILE):
    if not neutron_only:
        _run_midonet_post()

if os.path.isfile(migration_defs.NEUTRON_POST_COMMAND_FILE):
    if not midonet_only:
        _run_neutron_post()
