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

import os

NEUTRON_POST_COMMAND_FILE = "./migration_data/neutron_post_data"
ZK_BACKUP_FILENAME = "./migration_data/zk_original.backup"
TEMP_MN_CONF_SETTINGS = "./migration_data/mn-conf.settings"
MIDONET_POST_COMMAND_FILE = "./migration_data/midonet_post_data"

UPLINK_ROUTER_NAME = 'uplink_router'

if not os.path.isdir('./migration_data'):
    os.mkdir('./migration_data')

MIDONET_PLUGIN_CONF_FILE = "/etc/neutron/plugin.ini"
if not os.path.isfile(MIDONET_PLUGIN_CONF_FILE):
    MIDONET_PLUGIN_CONF_FILE = (
        "/etc/neutron/plugins/midonet/midonet_plugin.ini")
