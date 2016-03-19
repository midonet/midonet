#!/usr/bin/env python
#  Copyright (C) 2016 Midokura SARL
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


from data_migration.upgrade_base import get_zk_server
from data_migration.upgrade_base import temp_mn_conf_settings
from data_migration.upgrade_base import zk_backup_filename
import logging
import subprocess

log = logging.getLogger(name='data_migration/1.9.8')
""":type: logging.Logger"""

zk_server = get_zk_server()

subprocess.call('zkdump -z ' + str(zk_server) + ' -d -o ' +
                zk_backup_filename,
                stderr=subprocess.STDOUT,
                shell=True)

subprocess.call('cat ' + temp_mn_conf_settings + ' | mn-conf set',
                stderr=subprocess.STDOUT,
                shell=True)
