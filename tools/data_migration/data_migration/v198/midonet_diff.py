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

from data_migration.mn_defs import KeyMatchFilter
from data_migration.mn_defs import ListFilter
from data_migration.mn_defs import MidonetQuery
from data_migration.mn_defs import MinLengthFilter
from data_migration.mn_defs import NeutronQuery
from data_migration.mn_defs import UpgradeScriptException

import logging
import midonet.neutron.db.task_db as task
from midonet.neutron import plugin_v2
from neutron.common import config
import neutron.common.rpc as rpc
from neutron import context
from neutron_lbaas.db.loadbalancer import loadbalancer_db
from oslo_config import cfg

log = logging.getLogger(name='data_migration/1.9.8')
""":type: logging.Logger"""

try:
    cfg.CONF(args=[], project='neutron')
    cfg.CONF.register_opts(config.core_opts)
    rpc.init(cfg.CONF)
    mn_conf = cfg.CONF.MIDONET
    ctx = context.get_admin_context()

except cfg.ConfigFilesPermissionDeniedError as e:
    raise UpgradeScriptException('Error opening file; this tool must be run '
                                 'with root permissions: ' + str(e))

client = plugin_v2.MidonetPluginV2()
lb_client = loadbalancer_db.LoadBalancerPluginDb()
api_url = mn_conf.midonet_uri


midonet_queries = [
    MidonetQuery(key='mn_hosts', sub_key='host',
                 rest_key="hosts", api_url=api_url,
                 subquery_list=[
                     MidonetQuery(
                         key='ports',
                         rest_key="ports", api_url=api_url,
                         mapping_primary_key='interfaceName',
                         query_filter=KeyMatchFilter('hosts'))]),
    MidonetQuery(key='mv_tzs', sub_key='tz',
                 rest_key="tunnel_zones", api_url=api_url),
    MidonetQuery(key='mn_bgps', sub_key='bgp',
                 rest_key="bgps", api_url=api_url),
    MidonetQuery(key='mv_vteps', sub_key='vtep',
                 rest_key="vteps", api_url=api_url),
    MidonetQuery(key='mv_ip_groups', sub_key='ip_group',
                 rest_key="ip_addr_groups", api_url=api_url),
    MidonetQuery(key='mv_lbs', sub_key='lb',
                 rest_key="load_balancers", api_url=api_url),
    MidonetQuery(key='mv_port_groups', sub_key='port_group',
                 rest_key="port_groups", api_url=api_url),
]
""" :type: list[MidonetQuery]"""


def _inspect():
    log.info("Preparing to migrate from 1.9 to 5.0 MN topology")
    log.info("Note: Neutron DB data will be unaffected. Only new "
             "5.0 MN data will be created (1.9 data will remain as backup).")
    log.info("Using MidoNet API: " + api_url)

    topology_map = {}

    for query in midonet_queries:
        topology_map.update(query.get_objects(log=log))

    return topology_map


def migrate(debug=False, dry_run=False, from_version='v1.9.8'):

    if from_version != 'v1.9.8':
        raise ValueError('This script can only be run to migrate from '
                         'MidoNet version v1.9.8')

    log.setLevel(level=logging.DEBUG if debug else logging.INFO)
    stdout_handler = logging.StreamHandler()
    stdout_handler.setLevel(level=logging.DEBUG if debug else logging.INFO)
    log.addHandler(stdout_handler)

    old_topo = _inspect()

    log.info('Running migration process on topology')

    task_transaction_list = []
    for query in neutron_queries:
        task_transaction_list += query.create_tasks(log=log, topo=old_topo)

    log.debug('Task transaction ready')
    #log.debug(pprint.pformat(task_transaction_list, indent=2))

    for task_args in task_transaction_list:
        if dry_run:
            log.info('Would add task: ' +
                     ', '.join([
                         task_args['type'],
                         task_args['data_type'],
                         task_args['resource_id']]))
        else:
            log.debug('Creating a task in the task table with parameters: ' +
                      str(task_args))
            task.create_task(ctx, **task_args)
