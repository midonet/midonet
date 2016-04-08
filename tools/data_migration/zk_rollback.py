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

from data_migration.migration_defs import ZK_BACKUP_FILENAME
from data_migration.migration_funcs import get_zk_server
from data_migration.migration_funcs import UpgradeScriptException
import logging
from neutron.common import config
import neutron.common.rpc as rpc
from oslo_config import cfg
from oslo_db import options as db_options
import subprocess
import urlparse

log = logging.getLogger(name='data_migration/1.9.8')
""":type: logging.Logger"""

try:
    cfg.CONF(args=[], project='neutron')
    cfg.CONF.register_opts(config.core_opts)
    db_options.set_defaults(cfg.CONF)
    rpc.init(cfg.CONF)

except cfg.ConfigFilesPermissionDeniedError as e:
    raise UpgradeScriptException('Error opening file; this tool must be run '
                                 'with root permissions: ' + str(e))


db_conn_url = urlparse.urlparse(cfg.CONF['database']['connection'])
db_user = db_conn_url.username
db_pass = db_conn_url.password

zk_server = get_zk_server()

subprocess.call('zkdump -z ' + str(zk_server) + ' -l -i ' +
                ZK_BACKUP_FILENAME,
                stderr=subprocess.STDOUT,
                shell=True)

subprocess.call('mysql -u ' + db_user + ' --password=' + db_pass +
                " -D neutron -e 'DELETE FROM midonet_tasks;'",
                stderr=subprocess.STDOUT,
                shell=True)
