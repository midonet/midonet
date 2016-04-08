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

from data_migration.migration_config import MigrationConfig
from data_migration.migration_defs import MIDONET_POST_COMMAND_FILE
from data_migration.migration_defs import NEUTRON_POST_COMMAND_FILE
from data_migration.migration_defs import TEMP_MN_CONF_SETTINGS
from data_migration.migration_funcs import get_neutron_objects
from data_migration.migration_funcs import ListFilter
from data_migration.migration_funcs import MinLengthFilter
from data_migration.migration_funcs import UpgradeScriptException
import json
import logging
import midonet.neutron.db.task_db as task
import mn_object_defs
import urlparse

log = logging.getLogger(name='data_migration/1.9.8')
""":type: logging.Logger"""

cfg = MigrationConfig()


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
    subnets = cfg.client.get_subnets(context=context)
    for subnet in subnets:
        subnet_id = subnet['id']
        subnet_gw_ip = subnet['gateway_ip']
        interfaces = cfg.client.get_ports(context=context, filters=filters)
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


def _inspect_mn_fixture_constructs(topo):
    topo['mn_hosts'] = {}
    topo['mn_tzs'] = []

    routers = cfg.mn_get_objects('routers')
    provider_router = next(
        r
        for r in routers
        if r['name'] == "MidoNet Provider Router")

    log.debug("\n[(MIDONET) Provider Router]: " + str(provider_router))
    topo['mn_provider_router'] = {}
    pr_id = provider_router['id']
    pr_map = topo['mn_provider_router']

    pr_ports = cfg.mn_get_objects('routers/' + pr_id + '/ports')
    pr_map['ext_ports'] = []
    pr_map['routes'] = []

    # Seed the set of IPs that will be used to cehck which routes to
    # drop and which to keep (ignore routes for FIPs, VIPs, tenant routers,
    # external network, external ports, the DHCP metadata port, and any port
    # on the provider router itself).
    ips_to_drop_route = set()

    ips_to_drop_route.add('169.254.169.254/32')

    for p in pr_ports:
        if p['type'] == 'ExteriorRouter' and p['hostInterfacePort']:
            hip = cfg.mn_get_objects(full_url=p['hostInterfacePort'])
            pr_map['ext_ports'].append((p, hip))
            log.debug("\t[(MIDONET) Provider Router Ext Port]: port=" +
                      str(p) + " / interface=" + str(hip))
        ips_to_drop_route.add(p['portAddress'] + '/32')

    for fip in topo['floating-ips'].itervalues():
        ips_to_drop_route.add(fip['floating_ip_address'] + '/32')

    for fip in topo['vips'].itervalues():
        ips_to_drop_route.add(fip['address'] + '/32')

    for r in topo['routers'].itervalues():
        if ('external_gateway_info' in r and
                'external_fixed_ips' in r['external_gateway_info']):
            for ip in r['external_gateway_info']['external_fixed_ips']:
                ipaddr = ip['ip_address']
                ips_to_drop_route.add(ipaddr + '/32')

    ext_networks = [net
                    for net in topo['networks'].itervalues()
                    if net['router:external']]
    topo['mn_ext_networks'] = ext_networks
    for net in ext_networks:
        for sub in net['subnets']:
            sub_obj = topo['subnets'][sub]
            ips_to_drop_route.add(sub_obj['cidr'])
            log.debug("\t[(MIDONET) Provider External Subnet]: " +
                      str(sub_obj))

    # Add the routes that were added by the user
    routes = cfg.mn_get_objects('routers/' + pr_id + '/routes')
    for r in routes:
        cidr_ip = r['dstNetworkAddr'] + '/' + str(r['dstNetworkLength'])
        if cidr_ip not in ips_to_drop_route:
            pr_map['routes'].append(r)
            log.debug("\t[(MIDONET) Provider Router Route]: " + str(r))

    log.debug("\n[(MIDONET) hosts]")
    hosts = cfg.mn_get_objects('hosts')
    for host in hosts if hosts else []:
        log.debug("\t[(MIDONET) host " + host['id'] + "]: " + str(host))
        topo['mn_hosts'][host['id']] = {}
        host_map = topo['mn_hosts'][host['id']]
        host_map['host'] = host
        host_map['ports'] = {}
        port_map = host_map['ports']

        # Skip ports for health monitors
        ports = cfg.mn_get_objects('hosts/' + host['id'] + "/ports")
        hm_dp_ifaces = [i[0:8] + "_hm_dp"
                        for i in topo['load-balancer-pools']]
        for port in [p
                     for p in ports
                     if p['interfaceName'] not in hm_dp_ifaces]:
            port_obj = cfg.mn_get_objects(full_url=port['port'])

            # Skip port bindings for external routers (provider
            # router device)
            if port_obj['deviceId'] != pr_id:
                log.debug("\t\t[(MIDONET) port binding " +
                          port['interfaceName'] +
                          "=" + port['portId'] + "]")
                port_map[port['interfaceName']] = port['portId']

    log.debug("\n[(MIDONET) tunnel zones]")
    tzs = cfg.mn_get_objects('tunnel_zones')
    for tz in tzs if tzs else []:
        log.debug("\t[(MIDONET) tz " + tz['id'] + "]: " + str(tz))
        hosts = cfg.mn_get_objects('tunnel_zones/' + tz['id'] + "/hosts")

        tz_map = {'tz': tz, 'hosts': {}}
        host_map = tz_map['hosts']

        for host in hosts:
            log.debug("\t\t[(MIDONET) tz host]: " + str(host))
            host_map[host['hostId']] = host

        topo['mn_tzs'].append(tz_map)


def _inspect_mn_objects(topo, migrate_changed_obejcts=False):
    log.info("Inspecting all MN objects that were created " +
             ("or changed " if migrate_changed_obejcts else "") +
             "by the MidoNet CLI or API, bypassing the Neutron DB")

    log.info("Note: Neutron DB data will be unaffected. Only new "
             "5.0 MN data will be created (1.9 data will remain as backup).")
    log.info("Using MidoNet API: " + cfg.mn_url)

    topo.update({
        "midonet": {}
    })

    obj_keys = [("bridges", "bridge"),
                ("routers", "router"),
                ("vteps", "vtep"),
                ("ip_addr_groups", "ip-addr-group"),
                ("port_groups", "port-group"),
                ("pools", "pool")]

    for obj_key, obj_type in obj_keys:
        root_url = cfg.mn_url + '/' + obj_key
        topo['midonet'][obj_key] = mn_fill_tree(
            obj_key, root_url, obj_type, topo)

    # Next do BGP.  These exist on router ports, as a property.  The port
    # itself can be exterior or interior or neither, but searching across
    # all ports on all routers for bgp field should find them all.

    # Finally, convert the chains/chain rules


def _check_if_should_skip(obj, obj_id, root_type, check_map):
    if root_type == "port":
        log.debug("Checking port: " + obj_id)
        # Special handling for ports
        if (obj['type'] == "InteriorBridge" or
                obj['type'] == "ExteriorBridge"):
            # Only include bridge ports not in neutron
            if obj_id in check_map:
                log.debug("Already in neutron DB, skipping")
                return True
        elif obj['type'] == "InteriorRouter":
            # Only include router ports their peer bridge-port not in
            # neutron (the router ports are mn-only and created when
            # the bridge port is created via neutron)
            if obj['peerId'] in check_map:
                log.debug("Peer already in neutron DB, skipping")
                return True
        elif (obj['type'] == "ExteriorRouter" and
              obj['hostInterfacePort']):
            # Don't include bound exterior ports (already handled with
            # provider router -> uplink code)
            log.debug("Exterior host interface port, skipping")
            return True
    elif root_type == "dhcp-subnet":
        # Special handling for DHCP subnets, since there is no "id" field
        # to check for existence in neutron.  We have to rely on the CIDR
        cidr = obj['subnetPrefix'] + "/" + str(obj['subnetLength'])
        log.debug("Checking dhcp: " + cidr)

        subnet_found = False
        for _, sub in check_map:
            if sub['cidr'] == cidr:
                subnet_found = True
                break
        if subnet_found:
            log.debug("CIDR already in neutron DB, skipping")
            return True
    elif root_type == "router":
        # For routers, do not add the provider router, as there is no
        # provider router in 5.0+
        log.debug("Checking router: " + obj_id)
        if (obj['name'] == 'MidoNet Provider Router' or
                obj_id in check_map):
            log.debug("Provider router, or already in neutron DB, "
                      "skipping")
            return True
    else:
        # In the general case, skip if the object is found in the
        # related neutron map (if there is one, always add if there
        # isn't one)
        log.debug("Checking ID: " + obj_id + " against neutron map: " +
                  str(check_map.keys()))
        if obj_id in check_map:
            log.debug("Already in neutron DB, skipping")
            return True

    return False


def mn_fill_tree(root, root_url, root_type, topo):
    object_type_def = mn_object_defs.mn_type_map[root_type]

    object_flat_attr = (object_type_def['flat_attributes']
                        if 'flat_attributes' in object_type_def
                        else [])
    object_recursed_attr = (object_type_def['recursive_attributes']
                            if 'recursive_attributes' in object_type_def
                            else {})
    object_id_field = (object_type_def['id_field']
                       if 'id_field' in object_type_def
                       else None)
    object_compare_target = (object_type_def['neutron_equivalent']
                             if 'neutron_equivalent' in object_type_def
                             else None)
    neutron_compare_map = (topo[object_compare_target]
                           if object_compare_target
                           else {})

    log.debug("[MIDONET " + root + "]")
    obj_list = cfg.mn_get_objects(full_url=root_url)
    new_obj_list = []
    for obj in obj_list if obj_list else []:
        obj_id = obj[object_id_field] if object_id_field in obj else ''

        if _check_if_should_skip(obj, obj_id, root_type, neutron_compare_map):
            continue

        new_obj_map = {}
        for key in object_flat_attr:
            new_obj_map[key] = obj[key]
        for k, v in object_recursed_attr.iteritems():
            sub_obj_list = mn_fill_tree(k, obj[k], v, topo)
            new_obj_map[k] = sub_obj_list

        log.debug("[NEW MIDONET " + str(root_type) + "(" +
                  str(obj_id) + ")] = " +
                  str(new_obj_map))
        new_obj_list.append(new_obj_map)
    return new_obj_list


def _create_uplink(topo):
    ext_networks = topo['mn_ext_networks']
    provider_router = topo['mn_provider_router']
    pr_ext_ports = provider_router['ext_ports']
    pr_routes = provider_router['routes']

    uplink_router_topo = {
        'name': 'uplink_router',
        'ext_subnets': [],
        'uplink_ports': [],
        'routes': []
    }

    for ext_net in ext_networks:
        for sid in ext_net['subnets']:
            uplink_router_topo['ext_subnets'].append(sid)

    for ext_port, host_port in pr_ext_ports:
        iface_name = host_port['interfaceName']
        host_id = host_port['hostId']
        host_name = topo['mn_hosts'][host_id]['host']['name']
        port_addr = ext_port['portAddress']
        port_mac = ext_port['portMac']
        cidr = (ext_port['networkAddress'] + "/" +
                str(ext_port['networkLength']))
        host_port_map = {'host': host_name,
                         'iface': iface_name,
                         'ip': port_addr,
                         'mac': port_mac,
                         'network_cidr': cidr
                         }
        uplink_router_topo['uplink_ports'].append(host_port_map)
    log.debug('Uplink router topo: ' + str(uplink_router_topo))

    for route in pr_routes:
        uplink_router_topo['routes'].append({
            'nexthop': route['nextHopGateway'],
            'destination':
                route['dstNetworkAddr'] + "/" +
                str(route['dstNetworkLength'])})
    return {'uplink_router': uplink_router_topo}


def _create_tz_and_host_bindings(topo):
    return {
        'tunnel_zones': topo['mn_tzs'],
        'hosts': topo['mn_hosts']}


def _create_mn_objects(topo):
    return topo['midonet']

# (topo map key, obj fetch func, list of Filter objects to run on fetch)
neutron_queries = [
    ('security-groups', cfg.client.get_security_groups, []),
    ('networks', cfg.client.get_networks, []),
    ('subnets', cfg.client.get_subnets, []),
    ('ports', cfg.client.get_ports, []),
    ('routers', cfg.client.get_routers, []),
    ('router-interfaces', cfg.client.get_ports,
     [ListFilter(check_key='device_owner',
                 check_list=['network:router_interface'])]),
    ('subnet-gateways', _get_subnet_router,
     [ListFilter(check_key='device_owner',
                 check_list=['network:router_interface'])]),
    ('floating-ips', cfg.client.get_floatingips, []),
    ('load-balancer-pools', cfg.lb_client.get_pools, []),
    ('members', cfg.lb_client.get_members, []),
    ('vips', cfg.lb_client.get_vips, []),
    ('health-monitors', cfg.lb_client.get_health_monitors,
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
            get_neutron_objects(key=key, func=func, context=cfg.ctx, log=log,
                                filter_list=filter_list))
    log.debug("Checking midonet fixtures (tunnel_zone, hosts, "
              "provider router)")
    _inspect_mn_fixture_constructs(topology_map)
    log.debug("Checking other midonet objects that might have been "
              "created without going through neutron")
    _inspect_mn_objects(topology_map, migrate_changed_obejcts=False)

    return topology_map


def migrate(debug=False, dry_run=False):

    log.setLevel(level=logging.DEBUG if debug else logging.INFO)

    old_topo = _prepare()

    log.info('Running migration process on topology')

    task_transaction_list = []
    for key, model, func in neutron_creates:
        for oid, obj in old_topo[key].iteritems():
            task_transaction_list.append(func(old_topo, model, oid, obj))

    post_neutron_map = {}
    post_midonet_map = {}

    post_neutron_map.update(_create_uplink(old_topo))

    post_midonet_map.update(_create_tz_and_host_bindings(old_topo))
    post_midonet_map.update(_create_mn_objects(old_topo))

    log.debug('Task transaction ready')

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
            task.create_task(cfg.ctx, **task_args)

            db_conn_url = urlparse.urlparse(
                cfg.neutron_config['database']['connection'])
            db_user = db_conn_url.username
            db_pass = db_conn_url.password

            with open(TEMP_MN_CONF_SETTINGS, 'w') as f:
                f.write(
                    "cluster {\n"
                    "neutron_importer {\n"
                    "enabled: true\n"
                    "connection_string: "
                    "\"jdbc:mysql://localhost:3306/neutron\"\n"
                    "user: " + str(db_user) + "\n"
                    "password: " + str(db_pass) + "\n"
                    "}\n"
                    "}\n")

    with open(NEUTRON_POST_COMMAND_FILE, "w") as f:
        f.write(json.dumps(post_neutron_map))

    with open(MIDONET_POST_COMMAND_FILE, 'w') as f:
        f.write(json.dumps(post_midonet_map))
