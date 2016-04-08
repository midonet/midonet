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

import json
import logging

from oslo_config import cfg as cmd_arg


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

    if 'disable_network_anti_spoof' in nt_map:
        for net_id in nt_map['disable_network_anti_spoof']:

            port_list = [p
                         for p in mc.client.get_ports(mc.ctx)
                         if p['network_id'] == net_id]

            for port in port_list:
                new_port = {"port": {
                    "allowed_address_pairs": [
                        {"ip_address": "0.0.0.0/0"}
                    ]
                }}
                if dry_run:
                    log.info('Would run update_port with: ' + str(new_port))
                else:
                    mc.client.update_port(mc.ctx, port['id'], new_port)


log.setLevel(level=logging.DEBUG if debug else logging.INFO)

log.info("Running Neutron post-migration configuration")
_run_neutron_post()
