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

from data_migration import constants as const
from data_migration import exceptions as exc
from neutron.common import config
from oslo_config import cfg

try:
    cfg.CONF(args=[], project='neutron',
             default_config_files=[const.NEUTRON_CONF_FILE,
                                   const.MIDONET_PLUGIN_CONF_FILE])
except cfg.ConfigFilesPermissionDeniedError as e:
    raise exc.UpgradeScriptException('Error opening file; this tool must be '
                                     'run with root permissions: ' + str(e))

cfg.CONF.register_opts(config.core_opts)
