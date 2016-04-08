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


from data_migration import migration_funcs
from oslo_config import cfg

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
    cfg.StrOpt(
        'zookeeper', short='z', default=None, dest='zk_server',
        help='Location of the ZooKeeper server in IP:PORT form.'),
    cfg.DictOpt(
        'params', short='p', default=dict(), dest='params',
        help='Special parameters to pass to migration script '
             'in "{k1: v1, k2: v2}" JSON-style format')
]

cli_conf = cfg.ConfigOpts()
cli_conf.register_cli_opts(parser_opts)

cli_conf()

debug = cli_conf['debug']
dry_run = cli_conf['dryrun']
script_params = cli_conf['params']

SCRIPT_PACKAGE_NAME = "v198"

try:
    migration_func = getattr(
        __import__(name='data_migration.' +
                        SCRIPT_PACKAGE_NAME +
                        '.neutron_migration',
                   fromlist=['migrate']), 'migrate')
except AttributeError:
    raise migration_funcs.UpgradeScriptException(
        'The migration module is present, but has no '
        '"migrate" entry point function.')
except ImportError:
    raise migration_funcs.UpgradeScriptException(
        'No module found in data_migration package.')

migration_func(debug=debug, dry_run=dry_run,
               **script_params)
