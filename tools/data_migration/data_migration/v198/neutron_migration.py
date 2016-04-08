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


from data_migration import migration_config
from data_migration import migration_defs
from data_migration import utils
from data_migration import exceptions
import json
import logging
import midonet.neutron.db.task_db as task
import mn_object_defs
import urlparse


log = logging.getLogger(name='data_migration/1.9.8')

cfg = migration_config.MigrationConfig()


def get_neutron_objects(key, func, context,
                        filter_list=list()):
    retmap = {key: {}}
    submap = retmap[key]

    log.debug("\n[" + key + "]")

    filters = {}
    for f in filter_list:
        new_filter = f.func_filter()
        if new_filter:
            filters.update({new_filter[0]: new_filter[1]})

    object_list = func(
        context=context,
        filters=filters if filters else None)

    for f in filter_list:
        f.post_filter(object_list)

    for obj in object_list:
        """:type: dict[str, any]"""
        if 'id' not in obj:
            raise exceptions.UpgradeScriptException(
                'Trying to parse an object with no ID field: ' + str(obj))

        singular_noun = (key[:-1]
                         if key.endswith('s')
                         else key)
        log.debug("\t[" + singular_noun + " " +
                  obj['id'] + "]: " + str(obj))

        submap[obj['id']] = obj

    return retmap


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
                        for k, v in iter(router_obj.items())
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
        raise exceptions.UpgradeScriptException(
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
        raise exceptions.UpgradeScriptException(
            "LB Pool's subnet has no associated gateway router: " + lb_obj)
    log.debug("Preparing " + task_model + ": " + str(pid) +
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


def _get_ips_to_ignore_routes(topo, router_id):
    # Seed the set of IPs that will be used to check which routes to
    # drop and which to keep (ignore routes for FIPs, VIPs, tenant routers,
    # external network, external ports, the DHCP metadata port, and any port
    # on the provider router itself).
    ips_to_drop_route = set()

    ips_to_drop_route.add('169.254.169.254/32')

    if router_id in topo['routers']:
        # If this is a neutron router that is being updated
        neutron_router = topo['routers'][router_id]
        if 'external_gateway_info' in neutron_router:
            # Default routes when external_gateway_info is set get
            # added automatically
            ips_to_drop_route.add('0.0.0.0/0')

    pr_ports = cfg.mn_get_objects('routers/' + router_id + '/ports')
    for p in pr_ports:
        # Add the port route, and the port's network route
        ips_to_drop_route.add(p['portAddress'] + '/32')
        ips_to_drop_route.add(p['networkAddress'] + '/' +
                              str(p['networkLength']))

    for fip in topo['floating-ips'].itervalues():
        ips_to_drop_route.add(fip['floating_ip_address'] + '/32')

    for fip in topo['vips'].itervalues():
        ips_to_drop_route.add(fip['address'] + '/32')

    gw_ips = _get_router_gateway_addresses(topo)
    ips_to_drop_route.update(gw_ips)

    ext_networks = [net
                    for net in topo['networks'].itervalues()
                    if net['router:external']]
    topo['mn_ext_networks'] = ext_networks
    for net in ext_networks:
        for sub in net['subnets']:
            sub_obj = topo['subnets'][sub]
            ips_to_drop_route.add(sub_obj['cidr'])

    for r in topo['routers'].itervalues():
        if ('external_gateway_info' in r and
                'external_fixed_ips' in r['external_gateway_info']):
            for ip in r['external_gateway_info']['external_fixed_ips']:
                ipaddr = ip['ip_address']
                ips_to_drop_route.add(ipaddr + '/32')

    return ips_to_drop_route


def _make_cidr_from_route_obj(route):
    return (route['dstNetworkAddr'] + '/' +
            str(route['dstNetworkLength']))


def _get_router_gateway_addresses(topo):
    ret = set()
    for r in topo['routers'].itervalues():
        if ('external_gateway_info' in r and
                'external_fixed_ips' in r['external_gateway_info']):
            for ip in r['external_gateway_info']['external_fixed_ips']:
                ipaddr = ip['ip_address']
                ret.add(ipaddr + '/32')
    return ret


def _inspect_mn_host_tz_provider_router(topo):
    topo['mn_hosts'] = {}
    topo['mn_tzs'] = []

    routers = cfg.mn_get_objects('routers')
    provider_router = next(
        r
        for r in routers
        if r['name'] == mn_object_defs.MIDONET_PROVIDER_ROUTER)

    log.debug("\n[(MIDONET) Provider Router]: " + str(provider_router))
    topo['mn_provider_router'] = {}
    pr_id = provider_router['id']
    pr_map = topo['mn_provider_router']

    pr_ports = cfg.mn_get_objects('routers/' + pr_id + '/ports')
    pr_map['ext_ports'] = []
    pr_map['int_ports'] = []
    pr_map['routes'] = []
    pr_map['bgp_info'] = []
    ips_to_drop_route = _get_ips_to_ignore_routes(topo, pr_id)
    for p in pr_ports:
        if 'bgps' in p:
            port_data = _fill_object_from_type(topo, p, "port")

            if 'bgps' in port_data and len(port_data['bgps']) > 0:
                bgp_data = port_data['bgps']
                pr_map['bgp_info'].append(bgp_data)

        if p['type'] == 'ExteriorRouter' and p['hostInterfacePort']:
            hip = cfg.mn_get_objects(full_url=p['hostInterfacePort'])
            pr_map['ext_ports'].append((p, hip))
            log.debug("\t[(MIDONET) Provider Router Ext Port]: port=" +
                      str(p) + " / interface=" + str(hip))
        else:
            gw_ips = _get_router_gateway_addresses(topo)

            if (p['portAddress'] != '169.254.255.1' and
                    (p['portAddress'] + "/32") not in gw_ips):
                pr_map['int_ports'].append(p)

    # Add only the routes that were added by the user
    routes = cfg.mn_get_objects('routers/' + pr_id + '/routes')
    for r in routes:
        cidr_ip = _make_cidr_from_route_obj(r)
        if (r['type'].lower() == "reject" or
                r['type'].lower() == "blackhole" or
                cidr_ip not in ips_to_drop_route):
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

    log.debug("Checking midonet fixtures (tunnel_zone, hosts, "
              "provider router)")
    _inspect_mn_host_tz_provider_router(topo)

    log.debug("Checking other midonet objects that might have been "
              "created without going through neutron")

    topo.update({
        "midonet": {},
        "mn_disable_network_anti_spoof": []
    })

    obj_keys = [("bridges", "bridge"),
                ("routers", "router"),
                ("port_groups", "port-group"),
                ("load_balancers", "load-balancer"),
                ("chains", "chain")]

    for obj_key, obj_type in obj_keys:
        root_url = cfg.mn_url + '/' + obj_key

        log.debug("[MIDONET " + obj_key + "=" + obj_type + "]")
        new_list = _mn_create_topo_tree(root_url, obj_type, topo)
        topo['midonet'][obj_key] = new_list
        if obj_type == "bridge":
            for br_id, bridge in iter(new_list.items()):
                if ('disableAntiSpoof' in bridge and
                        bridge['disableAntiSpoof']):
                    topo['mn_disable_network_anti_spoof'].append(br_id)

    # Record any BGP info on the provider router
    topo['midonet']['uplink_router_bgp'] = (
        topo['mn_provider_router']['bgp_info'])


def _check_if_should_skip(topo, obj, obj_id, root_type, check_map):
    if root_type == "port":
        log.debug("Checking port: " + obj_id)
        # Special handling for ports
        if (obj['type'] == "InteriorBridge" or
                obj['type'] == "ExteriorBridge"):
            # Only include bridge ports not in neutron, which are also not
            # automatically-generated gateway ports on the MidoNet Provider
            # Router.
            if obj_id in check_map:
                return True
            if 'peer' in obj and obj['peer']:
                peer_obj = cfg.mn_get_objects(full_url=obj['peer'])
                if 'device' in peer_obj and peer_obj['device']:
                    peer_device = cfg.mn_get_objects(
                        full_url=peer_obj['device'])
                    if (peer_device['name'] ==
                            mn_object_defs.MIDONET_PROVIDER_ROUTER):
                        return True

        elif obj['type'] == "InteriorRouter":
            # Only include router ports their peer bridge-port not in
            # neutron (the router ports are mn-only and created when
            # the bridge port is created via neutron)
            if obj['peerId'] in check_map:
                return True
        elif (obj['type'] == "ExteriorRouter" and
              obj['hostInterfacePort']):
            # Don't include bound exterior ports (already handled with
            # provider router -> uplink code)
            return True
    elif root_type == "dhcp-subnet":
        # Special handling for DHCP subnets, since there is no "id" field
        # to check for existence in neutron.  We have to rely on the CIDR
        cidr = obj_id + "/" + str(obj['subnetLength'])
        log.debug("Checking dhcp: " + cidr)

        subnet_found = False
        for sub in check_map.itervalues():
            if sub['cidr'] == cidr:
                subnet_found = True
                break
        if subnet_found:
            return True
    elif root_type == "dhcp-host":
        # Don't bother with DHCP hosts, no one adds these by hand
        return True
    elif root_type == "route":
        # Do not add the provider router's routes, or routes for IPs that
        # are slated to be skipped.
        router_id = obj['routerId']
        ips_to_ignore_for_routes = _get_ips_to_ignore_routes(topo, router_id)
        dst_cidr = _make_cidr_from_route_obj(obj)
        if (obj['type'].lower() != "reject" and
                obj['type'].lower() != "blackhole" and
                dst_cidr in ips_to_ignore_for_routes):
            return True
    elif root_type == "router":
        # For routers, do not add the provider router, as there is no
        # provider router in 5.0+
        log.debug("Checking router: " + obj_id)
        if obj_id in check_map:
            return True
    elif root_type == "chain":
        # Skip any of the system-generated chains
        # Users should NOT modify these!!
        if (obj['name'].startswith("OS_PRE_ROUTING_") or
                obj['name'].startswith("OS_POST_ROUTING_") or
                obj['name'].startswith("OS_PORT_") or
                obj['name'].startswith("OS_SG_")):
            return True
    else:
        # In the general case, skip if the object is found in the
        # related neutron map (if there is one, always add if there
        # isn't one)
        log.debug("Checking obj ID: " + obj_id)
        if obj_id in check_map:
            return True

    return False


def _fill_object_from_type(topo, obj, root_type, skip_depends=False):
    """
    This function will return a fill (with recursive data filled in) data
    map reflecting the MN object given cast to the type given, using the
    data provided in the topo.  The dependent flat and recursive attributes
    will be skipped if the parameter is set.
    """
    object_type_def = mn_object_defs.mn_type_map[root_type]
    object_dependent_attr = (object_type_def['dependent_attributes']
                             if 'dependent_attributes' in object_type_def
                             else [])
    object_dependent_list = (object_type_def['dependent_recursive_attributes']
                             if 'dependent_recursive_attributes'
                                in object_type_def
                             else {})
    object_update_attr = (object_type_def['update_attributes']
                          if 'update_attributes' in object_type_def
                          else [])
    object_recursed_attr = (object_type_def['recursive_attributes']
                            if 'recursive_attributes' in object_type_def
                            else {})

    ret_object_map = {}
    if not skip_depends:
        for key in object_dependent_attr:
            ret_object_map[key] = obj[key]

        for k, v in iter(object_dependent_list.items()):
            root_url = obj[k]
            if root_url:
                sub_obj_list = _mn_create_topo_tree(root_url, v, topo)
                if len(sub_obj_list) > 0:
                    ret_object_map[k] = sub_obj_list

    for key, default in object_update_attr:
        if obj[key] != default:
            ret_object_map[key] = obj[key]

    for k, v in iter(object_recursed_attr.items()):
        root_url = obj[k]
        if root_url:
            sub_obj_list = _mn_create_topo_tree(root_url, v, topo)
            if len(sub_obj_list) > 0:
                ret_object_map[k] = sub_obj_list

    return ret_object_map


def _mn_create_topo_tree(root_url, root_type, topo):
    """
    This will create a topology tree starting at "root" and
    filling in any subchildren based on the info provided in "topo".

    If an object is not seen in the neutron topology, an 'operation' field
    will be added, set to 'create.'  If an object is seen as already being
    present in the neutron topology (or otherwise skipped), its children will
    still be checked for changes, and if the children are changed/created, the
    'operation' field will be set to 'update.'  If the object will neither
    be created or updated, it will be omitted from the final tree.
    """

    object_type_def = mn_object_defs.mn_type_map[root_type]
    object_id_field = (object_type_def['id_field']
                       if 'id_field' in object_type_def
                       else None)
    object_compare_target = (object_type_def['neutron_equivalent']
                             if 'neutron_equivalent' in object_type_def
                             else None)
    neutron_compare_map = (topo[object_compare_target]
                           if object_compare_target
                           else {})
    media_type = (object_type_def['midonet_media_type']
                  if 'midonet_media_type' in object_type_def
                  else '*/*')

    obj_list = cfg.mn_get_objects(full_url=root_url, media_type=media_type)
    new_topo_map = {}
    for obj in obj_list if obj_list else []:
        obj_id = obj[object_id_field] if object_id_field in obj else ''

        # Never bother with anything in the provider router, or anything in
        # its tree, as that is handled through the fixtures function
        if (root_type == 'router' and
                obj['name'] == mn_object_defs.MIDONET_PROVIDER_ROUTER):
            log.debug("Skipping provider router")
            continue

        obj_should_skip = _check_if_should_skip(
            topo=topo, obj=obj, obj_id=obj_id,
            root_type=root_type, check_map=neutron_compare_map)
        new_obj_map = _fill_object_from_type(
            topo, obj, root_type, obj_should_skip)

        if len(new_obj_map) > 0:
            # Load balancer is special as it will never match a neutron
            # object, but instead it will search it's child pool list, which
            # may or may not match a neutron lb pool.  This will exclude
            # any neutron pool from the mn object map, so if a lb has no new
            # pools (i.e. they all matched neutron objects), don't create a
            # new lb objet at all.  If all the pools in the new objects are
            # mn-only pools, then mark the overall lb operation as "create"
            # otherwise, if one or more pools are neutron-existing, whether
            # they have no extra MN-only changes (added members, added vips,
            # etc.) or not, mark this LB as an "update".
            if root_type == 'load-balancer':
                if 'pools' in new_obj_map and len(new_obj_map['pools']) > 0:
                    op = 'create'

                    pool_list = cfg.mn_get_objects(full_url=obj['pools'])
                    if len(pool_list) != len(new_obj_map['pools']):
                        op = 'update'
                    for pool in new_obj_map['pools'].itervalues():
                        if pool['operation'] != 'create':
                            op = 'update'
                            break

                    new_obj_map['operation'] = op
                else:
                    continue
            else:
                new_obj_map['operation'] = ('create' if not obj_should_skip
                                            else 'update')

            log.debug("[NEW MIDONET (" +
                      new_obj_map['operation'] + ") " +
                      str(root_type) + "(" +
                      str(obj_id) + ")] = " +
                      str(new_obj_map))
            new_topo_map[obj_id] = new_obj_map

    return new_topo_map


def _create_neutron_objects(topo):
    ext_networks = topo['mn_ext_networks']
    provider_router = topo['mn_provider_router']
    pr_ext_ports = provider_router['ext_ports']
    pr_routes = provider_router['routes']
    disable_anti_spoof_list = topo['mn_disable_network_anti_spoof']

    uplink_router_topo = {
        'name': migration_defs.UPLINK_ROUTER_NAME,
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
    return {'uplink_router': uplink_router_topo,
            'disable_network_anti_spoof': disable_anti_spoof_list}


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
     [utils.ListFilter(
         check_key='device_owner',
         check_list=['network:router_interface'])]),
    ('subnet-gateways', _get_subnet_router,
     [utils.ListFilter(
         check_key='device_owner',
         check_list=['network:router_interface'])]),
    ('floating-ips', cfg.client.get_floatingips, []),
    ('load-balancer-pools', cfg.lb_client.get_pools, []),
    ('members', cfg.lb_client.get_members, []),
    ('vips', cfg.lb_client.get_vips, []),
    ('health-monitors', cfg.lb_client.get_health_monitors,
     [utils.MinLengthFilter(
         field='pools',
         min_len=1)]),
]


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


def _prepare():
    log.info("Preparing to migrate from 1.9 to 5.0 MN topology")
    log.info("Note: Neutron DB data will be unaffected. Only new "
             "5.0 MN data will be created (1.9 data will remain as backup).")

    topology_map = {}

    for key, func, filter_list in neutron_queries:
        topology_map.update(
            get_neutron_objects(
                key=key, func=func, context=cfg.ctx,
                filter_list=filter_list))
    _inspect_mn_objects(topology_map, migrate_changed_obejcts=False)

    return topology_map


def migrate(debug=False, dry_run=False):

    log.setLevel(level=logging.DEBUG if debug else logging.INFO)
    # soh = logging.StreamHandler()
    # soh.setLevel(level=logging.DEBUG if debug else logging.INFO)
    # log.addHandler(soh)

    old_topo = _prepare()

    log.info('Running migration process on topology')

    task_transaction_list = []
    for key, model, func in neutron_creates:
        for oid, obj in iter(old_topo[key].items()):
            new_task = func(old_topo, model, oid, obj)
            if new_task:
                task_transaction_list.append(new_task)

    post_neutron_map = {}
    post_midonet_map = {}

    post_neutron_map.update(_create_neutron_objects(old_topo))

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

            with open(migration_defs.TEMP_MN_CONF_SETTINGS, 'w') as f:
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

    with open(migration_defs.NEUTRON_POST_COMMAND_FILE, "w") as f:
        f.write(json.dumps(post_neutron_map))

    with open(migration_defs.MIDONET_POST_COMMAND_FILE, 'w') as f:
        f.write(json.dumps(post_midonet_map))
