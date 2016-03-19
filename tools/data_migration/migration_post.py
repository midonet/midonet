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


from data_migration.migration_funcs import UpgradeScriptException
from data_migration.migration_defs import midonet_post_command_file
from data_migration.migration_defs import neutron_post_command_file

import logging
from midonet.neutron import plugin_v2
from midonetclient import client as mn_client
from neutron.common import config
import neutron.common.rpc as rpc
from neutron import context as ncntxt
import neutronclient.neutron.client
import neutronclient.v2_0.client
from neutron_lbaas.db.loadbalancer import loadbalancer_db
import os
from oslo_config import cfg
from oslo_db import options as db_options

LOG = logging.getLogger(name='data_migration')
""":type: logging.Logger"""

# main
parser_opts = [
    cfg.BoolOpt(
        'dryrun', short='n', default=False, dest='dryrun',
        help='Perform a "dry run" and print out the examined '
             'information and actions that would normally be '
             'taken, before exiting.'),
    cfg.BoolOpt(
        'debug', short='d', default=False, dest='debug',
        help='Turn on debug logging (off by default).'),
]

cli_conf = cfg.ConfigOpts()
cli_conf.register_cli_opts(parser_opts)

cli_conf()

debug = cli_conf['debug']
dry_run = cli_conf['dryrun']

try:
    cfg.CONF(args=[], project='neutron')
    cfg.CONF.register_opts(config.core_opts)
    db_options.set_defaults(cfg.CONF)
    rpc.init(cfg.CONF)
    mn_conf = cfg.CONF.MIDONET
    ctx = ncntxt.get_admin_context()

except cfg.ConfigFilesPermissionDeniedError as e:
    raise UpgradeScriptException(
        'Error opening file; this tool must be run '
        'with root permissions: ' + str(e))

api_cli = mn_client.MidonetClient(mn_conf.midonet_uri, mn_conf.username,
                                  mn_conf.password,
                                  project_id=mn_conf.project_id)
api_url = mn_conf.midonet_uri
client = plugin_v2.MidonetPluginV2()


LOG.info("Running post-migration configuration")


if os.path.isfile(midonet_post_command_file):
    with open(midonet_post_command_file) as f:
        pass

if os.path.isfile(neutron_post_command_file):
    with open(neutron_post_command_file) as f:
        pass

client.create_router()
