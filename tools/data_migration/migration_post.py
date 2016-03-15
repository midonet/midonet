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


from data_migration.migration_config import MigrationConfig
from data_migration.migration_defs import MIDONET_POST_COMMAND_FILE
from data_migration.migration_defs import NEUTRON_POST_COMMAND_FILE

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

mc = MigrationConfig()


def _run_midonet_post():
    with open(MIDONET_POST_COMMAND_FILE) as f:
        log.debug('Post-processing midonet topology with data in: ' +
                  MIDONET_POST_COMMAND_FILE)
        mn_map = json.load(f)

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
                (new_tz.add_tunnel_zone_host()
                 .ip_address(host['ipAddress'])
                 .host_id(host['hostId'])
                 .create())

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


def _run_neutron_post():
    with open(NEUTRON_POST_COMMAND_FILE) as f:
        log.debug('Post-processing neutron topology with data in: ' +
                  NEUTRON_POST_COMMAND_FILE)
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
stdout_handler = logging.StreamHandler()
stdout_handler.setLevel(level=logging.DEBUG if debug else logging.INFO)
log.addHandler(stdout_handler)

log.info("Running post-migration configuration")

if os.path.isfile(MIDONET_POST_COMMAND_FILE):
    if not neutron_only:
        _run_midonet_post()

if os.path.isfile(NEUTRON_POST_COMMAND_FILE):
    if not midonet_only:
        _run_neutron_post()
