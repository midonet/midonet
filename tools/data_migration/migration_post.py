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
from data_migration.migration_defs import midonet_post_command_file
from data_migration.migration_defs import neutron_post_command_file

import json
import logging

import os
from oslo_config import cfg as cmd_arg

log = logging.getLogger(name='data_migration')
""":type: logging.Logger"""

cfg = MigrationConfig()


def _do_or_log(func, *params):
    if dry_run:
        log.info(func + ' with params: ' + str(params))
    else:
        eval(func)


def _run_midonet_post():
    with open(midonet_post_command_file) as f:
        log.debug('Post-processing midonet topology with data in: ' +
                  midonet_post_command_file)
        mn_map = json.load(f)

    for tz in mn_map['tunnel_zones'] if 'tunnel_zones' in mn_map else []:
        tz_obj = tz['tz']
        _do_or_log("cfg.mn_api.add_tunnel_zone()"
                   ".type(tz_obj['type'])"
                   ".name(tz_obj['name'])"
                   ".create()", tz_obj['type'], tz_obj['name'])
        """ :type: midonetclient.tunnel_zone.TunnelZone"""

        for host_id, host in tz['hosts'].iteritems():
            _do_or_log("new_tz.add_tunnel_zone_host()"
                       ".ip_address(host['ipAddress'])"
                       ".host_id(host['hostId'])"
                       ".create()", host['ipAddress'], host['hostId'])

    for host_id, host in (mn_map['hosts'].iteritems()
                          if 'hosts' in mn_map else []):
        host_obj = cfg.mn_api.get_host(host_id)
        for iface, port in host['ports'].iteritems():
            _do_or_log("cfg.mn_api.add_host_interface_port("
                       "host_obj,"
                       "port_id=port,"
                       "interface_name=iface)",
                       host_obj.get_name(), port, iface)


def _run_neutron_post():
    with open(neutron_post_command_file) as f:
        log.debug('Post-processing neutron topology with data in: ' +
                  neutron_post_command_file)
        nt_map = json.load(f)

    if 'uplink_router' in nt_map:
        ur = nt_map['uplink_router']
        ext_ports = ur['uplink_ports'] if 'uplink_ports' in ur else []
        ext_subnets = ur['ext_subnets'] if 'ext_subnets' in ur else []
        routes = ur['routes'] if 'routes' in ur else []

        upl_router = cfg.client.create_router(
            cfg.ctx,
            {'name': ur['name'],
             'tenant_id': 'admin'})['router']

        for port in ext_ports:
            base_name = port['host'] + "_" + port['iface']
            upl_net = cfg.client.create_network(
                cfg.ctx,
                {'network': {'name': base_name + "_uplink_net",
                             'tenant_id': 'admin'}})['network']
            upl_sub = cfg.client.create_subnet(
                cfg.ctx,
                {'subnet': {'name': base_name + "_uplink_subnet",
                            'network_id': upl_net['id'],
                            'ip_version': 4,
                            'cidr': port['ip'] + '/32',
                            'enable_dhcp': False,
                            'tenant_id': 'admin'}})['subnet']
            bound_port = cfg.client.create_port(
                cfg.ctx,
                {'port': {'name': base_name + "_uplink_port",
                          'tenant_id': 'admin',
                          'network_id': upl_net['id'],
                          'fixed_ips': [{'subnet_id': upl_sub['id'],
                                         'ip_address': port['ip'],
                                         }],
                          'binding:host_id': port['host'],
                          'binding:profile': {
                              'interface_name': port['iface']}}})['port']
            cfg.client.add_router_interface(
                cfg.ctx,
                upl_router['id'],
                {'port_id': bound_port['id']})

        for subnet in ext_subnets:
            cfg.client.add_router_interface(
                cfg.ctx,
                upl_router['id'],
                {'subnet_id': subnet})

        if len(routes) > 0:
            cfg.client.update_router(
                cfg.ctx,
                upl_router['id'],
                {'router': {'routes': routes}})
    # main
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

log.setLevel(level=logging.DEBUG if debug else logging.INFO)
stdout_handler = logging.StreamHandler()
stdout_handler.setLevel(level=logging.DEBUG if debug else logging.INFO)
log.addHandler(stdout_handler)

log.info("Running post-migration configuration")

if os.path.isfile(midonet_post_command_file):
    _run_midonet_post()

if os.path.isfile(neutron_post_command_file):
    _run_neutron_post()
