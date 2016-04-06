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

from midonet.neutron import plugin_v2
import midonetclient.client
import midonetclient.api
from migration_defs import MIDONET_PLUGIN_CONF_FILE
import migration_funcs
from neutron.common import config
import neutron.common.rpc as rpc
from neutron import context as ncntxt
from neutron_lbaas.db.loadbalancer import loadbalancer_db
from oslo_config import cfg
from oslo_db import options as db_options


class MigrationConfig(object):
    def __init__(self):
        try:
            cfg.CONF(
                args=[],
                project='neutron',
                default_config_files=[
                    '/etc/neutron/neutron.conf',
                    MIDONET_PLUGIN_CONF_FILE])

            cfg.CONF.register_opts(config.core_opts)
            db_options.set_defaults(cfg.CONF)
            rpc.init(cfg.CONF)

        except cfg.ConfigFilesPermissionDeniedError as e:
            raise migration_funcs.UpgradeScriptException(
                'Error opening file; this tool must be run '
                'with root permissions: ' + str(e))

        self.neutron_config = cfg.CONF

        self.ctx = ncntxt.get_admin_context()

        self.client = plugin_v2.MidonetPluginV2()
        self.lb_client = loadbalancer_db.LoadBalancerPluginDb()

        self.mn_config = self.neutron_config.MIDONET
        self.mn_url = self.mn_config.midonet_uri
        self.mn_client = midonetclient.client.httpclient.HttpClient(
            self.mn_config.midonet_uri,
            self.mn_config.username,
            self.mn_config.password,
            project_id=self.mn_config.project_id)
        self.mn_api = midonetclient.api.MidonetApi(
            self.mn_config.midonet_uri,
            self.mn_config.username,
            self.mn_config.password,
            project_id=self.mn_config.project_id)

    def mn_get_objects(self, path=None, full_url=None):
        if full_url and path:
            raise migration_funcs.UpgradeScriptException(
                "Can't specify both full URL and "
                "relative path to mn_get_objects")
        get_path = full_url if full_url else self.mn_url + '/' + path + '/'
        print "MN API Path: " + get_path
        data = self.mn_client.get(uri=get_path, media_type="*/*")
        return data

