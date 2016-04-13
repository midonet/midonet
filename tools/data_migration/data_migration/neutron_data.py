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

from data_migration import context as ctx
from data_migration import exceptions as exc
from data_migration import utils
import logging
import midonet.neutron.db.task_db as task

LOG = logging.getLogger(name="data_migration")
migration_context = ctx.migration_context


def _get_neutron_objects(key, func, context, filter_list=None):
    if filter_list is None:
        filter_list = []

    retmap = {key: {}}
    submap = retmap[key]

    LOG.debug("\n[" + key + "]")

    filters = {}
    for f in filter_list:
        new_filter = f.func_filter()
        if new_filter:
            filters.update({new_filter[0]: new_filter[1]})

    object_list = func(context=context, filters=filters if filters else None)

    for f in filter_list:
        f.post_filter(object_list)

    for obj in object_list:
        if 'id' not in obj:
            raise exc.UpgradeScriptException(
                'Trying to parse an object with no ID field: ' + str(obj))

        singular_noun = key[:-1] if key.endswith('s') else key
        LOG.debug("\t[" + singular_noun + " " + obj['id'] + "]: " + str(obj))

        submap[obj['id']] = obj

    return retmap


def _get_subnet_router(context, filters=None):
    new_list = []
    client = migration_context.client
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

        new_list.append({'id': subnet_id, 'gw_router_id': gw_id})
    return new_list


def _task_create_by_id(_topo, task_model, oid, obj):
    LOG.debug("Preparing " + task_model + ": " + str(oid))
    return {'type': "CREATE",
            'data_type': task_model,
            'resource_id': oid,
            'data': obj}


def _task_lb(topo, task_model, pid, lb_obj):
    lb_subnet = lb_obj['subnet_id']
    router_id = topo['subnet-gateways'][lb_subnet]['gw_router_id']
    if not router_id:
        raise exc.UpgradeScriptException(
            "LB Pool's subnet has no associated gateway router: " + lb_obj)
    LOG.debug("Preparing " + task_model + ": " + str(pid) +
              " on router " + router_id)
    new_lb_obj = lb_obj.copy()
    new_lb_obj['health_monitors'] = []
    new_lb_obj['health_monitors_status'] = []
    new_lb_obj['members'] = []
    new_lb_obj['vip_id'] = None
    new_lb_obj['router_id'] = router_id
    return {'type': task.CREATE,
            'data_type': task_model,
            'resource_id': pid,
            'data': new_lb_obj}


def _task_router(_topo, task_model, rid, router_obj):
    LOG.debug("Preparing " + task_model + ": " + str(rid))

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
    LOG.debug("Preparing " + task_model + " on ROUTER: " + str(pid) +
              " on router: " + router_id)
    if 'fixed_ips' not in port:
        raise exc.UpgradeScriptException(
            'Router interface port has no fixed IPs:' + str(port))
    subnet_id = port['fixed_ips'][0]['subnet_id']
    interface_dict = {'id': router_id,
                      'port_id': pid,
                      'subnet_id': subnet_id}
    return {'type': task.CREATE,
            'data_type': task_model,
            'resource_id': router_id,
            'data': interface_dict}


def _task_router_routes(_topo, task_model, rid, router_obj):
    # Update routes if present
    if 'routes' in router_obj:
        LOG.debug("Updating " + task_model + ": " + router_obj['id'])
        return {'type': task.UPDATE,
                'data_type': task_model,
                'resource_id': rid,
                'data': router_obj}
    return None


_CREATES = [
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


# (topo map key, obj fetch func, list of Filter objects to run on fetch)
_GET_QUERIES = [
    ('security-groups', migration_context.client.get_security_groups, []),
    ('networks', migration_context.client.get_networks, []),
    ('subnets', migration_context.client.get_subnets, []),
    ('ports', migration_context.client.get_ports, []),
    ('routers', migration_context.client.get_routers, []),
    ('router-interfaces', migration_context.client.get_ports,
     [utils.ListFilter(check_key='device_owner',
                       check_list=['network:router_interface'])]),
    ('subnet-gateways', _get_subnet_router,
     [utils.ListFilter(check_key='device_owner',
                 check_list=['network:router_interface'])]),
    ('floating-ips', migration_context.client.get_floatingips, []),
    ('load-balancer-pools', migration_context.lb_client.get_pools, []),
    ('members', migration_context.lb_client.get_members, []),
    ('vips', migration_context.lb_client.get_vips, []),
    ('health-monitors', migration_context.lb_client.get_health_monitors,
     [utils.MinLengthFilter(field='pools', min_len=1)]),
]


def _create_obj_map():
    """Creates a map of object ID -> object from Neutron DB"""
    obj_map = {}
    migration_context = ctx.migration_context
    for key, func, filter_list in _GET_QUERIES:
        obj_map.update(
            _get_neutron_objects(key=key, func=func,
                                 context=migration_context.ctx,
                                 filter_list=filter_list))
    return obj_map


def _create_task_list(obj_map):
    """Creates a list of tasks to run given a map of object ID -> object"""
    task_list = []
    for key, model, func in _CREATES:
        for oid, obj in obj_map[key].iteritems():
            elem = func(obj_map, model, oid, obj)
            if elem:
                task_list.append(elem)
    return task_list


def _dry_run_output(task):
    return 'Task: ' + ', '.join([task['type'], task['data_type'],
                                 task['resource_id']])


def migrate():
    LOG.info('Running migration process')
    obj_map = _create_obj_map()
    tasks = _create_task_list(obj_map)
    for t in tasks:
        print(_dry_run_output(t))
