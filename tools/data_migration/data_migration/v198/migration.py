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

from data_migration.upgrade_base import create_task_from_query
from data_migration.upgrade_base import get_objects_from_query
from data_migration.upgrade_base import IDFilter
from data_migration.upgrade_base import ListFilter
from data_migration.upgrade_base import MinLengthFilter
from data_migration.upgrade_base import TopoQuery
from data_migration.upgrade_base import UpgradeScriptException

import logging
import midonet.neutron.db.task_db as task
from midonet.neutron import plugin_v2
from neutron.common import config
import neutron.common.rpc as rpc
from neutron import context
from neutron_lbaas.db.loadbalancer import loadbalancer_db
from oslo_config import cfg
import pprint

log = logging.getLogger(name='data_migration/1.9.8')
""":type: logging.Logger"""

try:
    cfg.CONF(args=[], project='neutron')
    cfg.CONF.register_opts(config.core_opts)
    rpc.init(cfg.CONF)
    ctx = context.get_admin_context()

except cfg.ConfigFilesPermissionDeniedError as e:
    raise UpgradeScriptException('Error opening file; this tool must be run '
                                 'with root permissions: ' + str(e))

client = plugin_v2.MidonetPluginV2()
lb_client = loadbalancer_db.LoadBalancerPluginDb()


def _special_add_router_task(rid, router):

    task_list = []

    router_obj = router['router']
    """ :type: dict"""
    interfaces = router['interfaces']

    log.debug("Creating router translation for: " + str(rid))

    # Create a router with no routes and update them later
    route_obj = None
    if 'routes' in router_obj:
        route_obj = router_obj.pop('routes')

    task_list.append({'type': task.CREATE,
                      'data_type': task.ROUTER,
                      'resource_id': rid,
                      'tenant_id': router_obj['tenant_id'],
                      'data': router_obj})

    for iid, i in interfaces.iteritems():
        log.debug("\tCreating interface translation for: " + str(iid))
        if 'fixed_ips' not in i:
            raise UpgradeScriptException(
                'Router interface port has no fixed IPs:' + str(i))
        subnet_id = i['fixed_ips'][0]['subnet_id']
        interface_dict = {'id': rid,
                          'port_id': iid,
                          'subnet_id': subnet_id}
        task_list.append({'type': task.CREATE,
                          'data_type': "ROUTERINTERFACE",
                          'resource_id': rid,
                          'tenant_id': router_obj['tenant_id'],
                          'data': interface_dict})
    if route_obj:
        log.debug("\tAdding extra routes: " + str(route_obj))
        task_list.append({'type': task.UPDATE,
                          'data_type': task.ROUTER,
                          'resource_id': rid,
                          'tenant_id': router_obj['tenant_id'],
                          'data': route_obj})

    return task_list


neutron_queries = [
    TopoQuery(key='networks', func=client.get_networks, sub_key='network',
              task_model=task.NETWORK,
              subquery_list=[
                  TopoQuery(key='subnets', func=client.get_subnets,
                            task_model=task.SUBNET,
                            filter_list=[IDFilter(foreign_key='network_id')]),
                  TopoQuery(key='ports', func=client.get_ports,
                            task_model=task.PORT,
                            filter_list=[IDFilter(foreign_key='network_id')])]),
    TopoQuery(key='routers', func=client.get_routers, sub_key='router',
              task_model=task.ROUTER,
              subquery_list=[
                  TopoQuery(key='interfaces', func=client.get_ports,
                            task_model="ROUTER_INTERFACE",
                            filter_list=[
                                IDFilter(foreign_key='device_id'),
                                ListFilter(
                                    check_key='device_owner',
                                    check_list=['network:router_interface',
                                                'network:router_gateway'])])],
              task_custom_add=_special_add_router_task),
    TopoQuery(key='floating-ips', func=client.get_floatingips,
              task_model=task.FLOATING_IP),
    TopoQuery(key='load-balancer-pools', func=lb_client.get_pools,
              sub_key='pool',
              task_model=task.POOL,
              subquery_list=[
                  TopoQuery(key='members', func=lb_client.get_members,
                            task_model=task.MEMBER,
                            filter_list=[IDFilter('pool_id')]),
                  TopoQuery(key='vips', func=lb_client.get_vips,
                            task_model=task.VIP,
                            filter_list=[IDFilter('pool_id')])]),
    TopoQuery(key='health-monitors', func=lb_client.get_health_monitors,
              task_model=task.HEALTH_MONITOR,
              filter_list=[MinLengthFilter('pools')]),
    TopoQuery(key='security-groups', func=client.get_security_groups,
              task_model=task.SECURITY_GROUP)
]
""" :type: list[TopoQuery]"""


def _prepare():
    log.info("Preparing to migrate from 1.9 to 5.0 MN topology")
    log.info("Note: Neutron DB data will be unaffected. Only new "
             "5.0 MN data will be created (1.9 data will remain as backup).")

    topology_map = {}

    for query in neutron_queries:
        topology_map.update(
            get_objects_from_query(log=log,
                                   ctx=ctx,
                                   query=query))

    return topology_map


def migrate(debug=False, dry_run=False, from_version='v1.9.8'):

    if from_version != 'v1.9.8':
        raise ValueError('This script can only be run to migrate from '
                         'MidoNet version v1.9.8')

    log.setLevel(level=logging.DEBUG if debug else logging.INFO)
    stdout_handler = logging.StreamHandler()
    stdout_handler.setLevel(level=logging.DEBUG if debug else logging.INFO)
    log.addHandler(stdout_handler)

    old_topo = _prepare()

    log.info('Running migration process on topology')
    log.info('Topology to migrate:')
    log.info(pprint.pformat(old_topo, indent=1))

    task_transaction_list = []
    for query in neutron_queries:
        task_transaction_list += create_task_from_query(log=log,
                                                        topo=old_topo,
                                                        query=query)

    log.debug('Task transaction ready')
    log.debug(pprint.pformat(task_transaction_list, indent=2))

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
