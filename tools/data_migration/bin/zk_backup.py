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


from data_migration import migration_defs
import logging
import package_utils
import subprocess

log = logging.getLogger(name='data_migration/1.9.8')
""":type: logging.Logger"""

zk_server = package_utils.get_zk_server()

subprocess.call('zkdump -z ' + str(zk_server) + ' -d -o ' +
                migration_defs.ZK_BACKUP_FILENAME,
                stderr=subprocess.STDOUT,
                shell=True)
