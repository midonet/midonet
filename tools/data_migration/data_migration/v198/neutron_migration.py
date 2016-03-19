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

from data_migration.upgrade_base import get_neutron_objects
from data_migration.upgrade_base import ListFilter
from data_migration.upgrade_base import MinLengthFilter
from data_migration.upgrade_base import temp_mn_conf_settings
from data_migration.upgrade_base import UpgradeScriptException

import logging
import midonet.neutron.db.task_db as task
from midonet.neutron import plugin_v2
from midonetclient import client as mn_client
from neutron.common import config
import neutron.common.rpc as rpc
from neutron import context as ncntxt
from neutron_lbaas.db.loadbalancer import loadbalancer_db
from oslo_config import cfg
from oslo_db import options as db_options
import urlparse

log = logging.getLogger(name='data_migration/1.9.8')
""":type: logging.Logger"""

try:
    cfg.CONF(args=[], project='neutron')
    cfg.CONF.register_opts(config.core_opts)
    db_options.set_defaults(cfg.CONF)
    rpc.init(cfg.CONF)
    mn_conf = cfg.CONF.MIDONET
    ctx = ncntxt.get_admin_context()

except cfg.ConfigFilesPermissionDeniedError as e:
    raise UpgradeScriptException('Error opening file; this tool must be run '
                                 'with root permissions: ' + str(e))

client = plugin_v2.MidonetPluginV2()
lb_client = loadbalancer_db.LoadBalancerPluginDb()

api_cli = mn_client.MidonetClient(mn_conf.midonet_uri, mn_conf.username,
                                  mn_conf.password,
                                  project_id=mn_conf.project_id)


def _task_create_by_id(_, task_model, oid, obj):
    log.debug("Preparing " + task_model + ": " + str(oid))

    return {'type': "CREATE",
            'data_type': task_model,
            'resource_id': oid,
            'data': obj}


def _task_router(_, task_model, rid, router_obj):
    log.debug("Preparing " + task_model + ": " + str(rid))

    # Create a router with no routes and update them later
    routeless_router = {k: v
                        for k, v in router_obj.iteritems()
                        if k != 'routes'}

    return {'type': task.CREATE,
            'data_type': task_model,
            'resource_id': rid,
            'data': routeless_router}


def _task_router_interface(topo, task_model, pid, port):

    router_obj = topo['routers'][port['device_id']]
    router_id = router_obj['id']
    log.debug("Preparing"
              " " + task_model + " on ROUTER: " + str(pid) +
              " on router: " + router_id)
    if 'fixed_ips' not in port:
        raise UpgradeScriptException(
            'Router interface port has no fixed IPs:' + str(port))
    subnet_id = port['fixed_ips'][0]['subnet_id']
    interface_dict = {'id': router_id,
                      'port_id': pid,
                      'subnet_id': subnet_id}
    return {'type': task.CREATE,
            'data_type': task_model,
            'resource_id': router_id,
            'data': interface_dict}


def _task_router_routes(_, task_model, rid, router_obj):
    # Update routes if present
    if 'routes' in router_obj:
        log.debug("Updating " + task_model + ": " + router_obj['id'])
        return {'type': task.UPDATE,
                'data_type': task_model,
                'resource_id': rid,
                'data': router_obj}

    return None


def _task_lb(topo, task_model, pid, lb_obj):
    lb_subnet = lb_obj['subnet_id']
    router_id = topo['subnet-gateways'][lb_subnet]['gw_router_id']
    if not router_id:
        raise UpgradeScriptException(
            "LB Pool's subnet has no associated gateway router: " + lb_obj)
    log.debug("Preparing " + task_model + ": " + str(pid) +
              " on router " + router_id)
    new_lb_obj = lb_obj.copy()
    """ :type: dict[str, any]"""
    new_lb_obj['health_monitors'] = []
    new_lb_obj['health_monitors_status'] = []
    new_lb_obj['members'] = []
    new_lb_obj['vip_id'] = None
    new_lb_obj['router_id'] = router_id
    return {'type': task.CREATE,
            'data_type': task_model,
            'resource_id': pid,
            'data': new_lb_obj}


def _get_subnet_router(context, filters=None):
    new_list = []
    subnets = client.get_subnets(context=context)
    for subnet in subnets:
        subnet_id = subnet['id']
        subnet_gw_ip = subnet['gateway_ip']
        interfaces = client.get_ports(context=context, filters=filters)
        gw_iface = next(
            (i for i in interfaces
                if ('fixed_ips' in i and len(i['fixed_ips']) > 0 and
                    i['fixed_ips'][0]['ip_address'] == subnet_gw_ip and
                    i['fixed_ips'][0]['subnet_id'] == subnet_id)),
            None)
        gw_id = None
        if gw_iface:
            gw_id = gw_iface['device_id']

        new_list.append({'id': subnet_id,
                         'gw_router_id': gw_id})
    return new_list


def _create_uplink(topo, pub_net_id,
                   host_name, host_interface):
    pub_net = topo['networks'][pub_net_id]
    if not pub_net:
        raise UpgradeScriptException(
            "Can't add uplink GW because public netowrk not found: " +
            pub_net_id)

    if 'subnets' not in pub_net:
        raise UpgradeScriptException(
            "Can't add uplink GW because no public subnet found "
            "in public network")

    pub_subnet = pub_net['subnets'][0]
    pub_cidr = pub_subnet['cidr']
    pub_gw_ip = pub_subnet['gateway_ip']


# (topo map key, obj fetch func, list of Filter objects to run on fetch)
neutron_queries = [
    ('security-groups', client.get_security_groups, []),
    ('networks', client.get_networks, []),
    ('subnets', client.get_subnets, []),
    ('ports', client.get_ports, []),
    ('routers', client.get_routers, []),
    ('router-interfaces', client.get_ports,
     [ListFilter(check_key='device_owner',
                 check_list=['network:router_interface'])]),
    ('subnet-gateways', _get_subnet_router,
     [ListFilter(check_key='device_owner',
                 check_list=['network:router_interface'])]),
    ('floating-ips', client.get_floatingips, []),
    ('load-balancer-pools', lb_client.get_pools, []),
    ('members', lb_client.get_members, []),
    ('vips', lb_client.get_vips, []),
    ('health-monitors', lb_client.get_health_monitors,
     [MinLengthFilter(field='pools',
                      min_len=1)]),
]
""" :type: list[(str, callable, list[QueryFilter] """


neutron_creates = [
    ('security-groups', task.SECURITY_GROUP, _task_create_by_id),
    ('networks', task.NETWORK, _task_create_by_id),
    ('subnets', task.SUBNET, _task_create_by_id),
    ('ports', task.PORT, _task_create_by_id),
    ('routers', task.ROUTER, _task_router),
    ('router-interfaces', "ROUTERINTERFACE", _task_router_interface),
    ('routers', task.ROUTER, _task_router_routes),
    ('floating-ips', task.FLOATING_IP, _task_create_by_id),
    ('load-balancer-pools', task.POOL, _task_lb),
    ('members', task.MEMBER, _task_create_by_id),
    ('vips', task.VIP, _task_create_by_id),
    ('health-monitors', task.HEALTH_MONITOR, _task_create_by_id)
]
""" :type: list[(str, str, callable|None)] """


def _prepare():
    log.info("Preparing to migrate from 1.9 to 5.0 MN topology")
    log.info("Note: Neutron DB data will be unaffected. Only new "
             "5.0 MN data will be created (1.9 data will remain as backup).")

    topology_map = {}

    for key, func, filter_list in neutron_queries:
        topology_map.update(
            get_neutron_objects(key=key, func=func, context=ctx, log=log,
                                filter_list=filter_list))

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

    task_transaction_list = []
    for key, model, func in neutron_creates:
        for oid, obj in old_topo[key].iteritems():
            task_transaction_list.append(func(old_topo, model, oid, obj))

    log.debug('Task transaction ready')
    # log.debug(pprint.pformat(task_transaction_list, indent=2))

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

    db_conn_url = urlparse.urlparse(cfg.CONF['database']['connection'])
    db_user = db_conn_url.username
    db_pass = db_conn_url.password

    with open(temp_mn_conf_settings, 'w') as f:
        f.write(
            "cluster {\n"
            "  neutron_importer {\n"
            "    enabled: true\n"
            "    connection_string: \"jdbc:mysql://localhost:3306/neutron\"\n"
            "    user: " + str(db_user) + "\n"
            "    password: " + str(db_pass) + "\n"
            "  }\n"
            "}\n")
