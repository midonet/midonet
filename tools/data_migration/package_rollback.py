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

from data_migration.migration_funcs import install_packages
from data_migration.migration_funcs import remove_packages
from data_migration.migration_funcs import restart_service
from data_migration.migration_funcs import update_api_endpoints
from data_migration.migration_funcs import update_repos
import logging
import subprocess

log = logging.getLogger(name='data_migration/1.9.8')
""":type: logging.Logger"""

remove_packages(['midolman', 'python-midonetclient'])


update_repos('midonet-5', 'mem-1.9')
update_api_endpoints(to_port=8080)
install_packages(['midolman', 'python-midonetclient', 'midonet-api'])

restart_service('neutron-server')
restart_service('tomcat7')
subprocess.call('sudo rm /usr/bin/wdog',
                stderr=subprocess.STDOUT,
                shell=True)
