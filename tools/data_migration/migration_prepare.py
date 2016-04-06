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
from oslo_config import cfg

valid_from_versions = ['v1.9.8']

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
        help='Location of the '),
    cfg.StrOpt(
        'from', short='f',
        default=valid_from_versions[0], dest='from_version',
        help='Specify which version you are upgrading from.  '
             'Valid values are ' + str(valid_from_versions) +
             '. defualt: ' + valid_from_versions[0]),
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
from_version = cli_conf['from_version']

script_package_name = from_version.replace('.', '')
valid_packages = [i.replace('.', '') for i in valid_from_versions]

if script_package_name not in valid_packages:
    raise ValueError('Error: ' + from_version + ' is not found in the list ' +
                     'of valid versions: ' + str(valid_from_versions))

script_module = None
try:
    script_module = __import__(name='data_migration.' +
                                    script_package_name +
                                    '.neutron_migration',
                               fromlist=['migrate'])
except ImportError:
    raise UpgradeScriptException('No module found in data_migration '
                                 'package: ' + script_package_name)

migration_func = None
try:
    migration_func = getattr(script_module, 'migrate')
except AttributeError:
    raise UpgradeScriptException('The "' + script_package_name +
                                 '" module is present, but has no "'
                                 '"migrate" entry point function.')

migration_func(debug=debug, dry_run=dry_run,
               from_version=from_version,
               **script_params)
