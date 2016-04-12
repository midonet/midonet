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

from data_migration import config  # noqa
from neutron.common import rpc
from neutron import context as ncntxt
from neutron.plugins.midonet import plugin
from neutron_lbaas.db.loadbalancer import loadbalancer_db
from oslo_config import cfg


class MigrationContext(object):

    def __init__(self):

        # Required to bypass an error when instantiating Midonet plugin.
        rpc.init(cfg.CONF)

        self.ctx = ncntxt.get_admin_context()
        self.client = plugin.MidonetPluginV2()
        self.lb_client = loadbalancer_db.LoadBalancerPluginDb()


migration_context = MigrationContext()
