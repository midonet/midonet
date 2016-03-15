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


import data_migration.upgrade_base as upg
import logging
import midonet.neutron.db.task_db as task
from midonet.neutron import plugin_v2
from neutron.common import config
import neutron.common.rpc as rpc
from neutron import context
from neutron_lbaas.db.loadbalancer import loadbalancer_db
from oslo_config import cfg
import pprint


def _prepare(log, ctx):
    log.info("Preparing to migrate from 1.9 to 5.0 MN topology")
    log.info("Note: Neutron DB data will be unaffected. Only new "
             "5.0 MN data will be created (1.9 data will remain as backup).")
    client = plugin_v2.MidonetPluginV2()
    lb_client = loadbalancer_db.LoadBalancerPluginDb()

    topology_map = {'networks': {},
                    'routers': {},
                    'floating-ips': {},
                    'load-balancer-pools': {},
                    'health-monitors': {},
                    'security-groups': {}
                    }

    log.debug("\n[networks]")
    networks = client.get_networks(ctx)
    for n in networks:
        log.debug("\n\t[network " + n['id'] + "]: " + str(n))
        topology_map['networks'][n['id']] = {}
        network_map = topology_map['networks'][n['id']]
        network_map['network'] = n
        network_map['subnets'] = {}
        network_map['ports'] = {}
        log.debug("\t\t[subnets]")
        subnets = client.get_subnets(ctx, filters={'network_id': [n['id']]})
        for s in subnets:
            log.debug("\t\t\t[subnet " + s['id'] + "]: " + str(s))
            network_map['subnets'][s['id']] = s

        log.debug("\t\t[ports]")
        ports = client.get_ports(ctx,
                                 filters={'network_id': [n['id']]})

        for p in ports:
            log.debug("\t\t\t[network port " + p['id'] + "]: " + str(p))
            network_map['ports'][p['id']] = p

    log.debug("\n[routers]")
    routers = client.get_routers(ctx)
    for r in routers:
        log.debug("\n\t[router " + r['id'] + "]: " + str(r))
        topology_map['routers'][r['id']] = {}
        router_map = topology_map['routers'][r['id']]
        router_map['router'] = r
        router_map['interfaces'] = {}

        log.debug("\t\t[interfaces]")
        interfaces = client.get_ports(
            ctx,
            filters={'device_id': [r['id']],
                     'device_owner': ['network:router_interface',
                                      'network:router_gateway']})
        for i in interfaces:
            log.debug("\t\t\t[router interface " + i['id'] + "]: " + str(i))
            router_map['interfaces'][i['id']] = i

    log.debug("\n[floating-ips]")
    floatingips = client.get_floatingips(ctx)
    for fip in floatingips:
        log.debug("\n\t[floating-ip " + fip['id'] + "]: " + str(fip))
        topology_map['floating-ips'][fip['id']] = fip

    log.debug("\n[load-balancer-pools]")
    pools = lb_client.get_pools(ctx)
    for p in pools:
        log.debug("\n\t[load-balancer-pool" + p['id'] + "]: " + str(p))
        topology_map['load-balancer-pools'][p['id']] = {}
        pool_map = topology_map['load-balancer-pools'][p['id']]
        pool_map['pool'] = p
        pool_map['members'] = {}
        pool_map['vips'] = {}

        log.debug("\t\t[members]")
        members = lb_client.get_members(
            ctx,
            filters={'pool_id': [p['id']]})
        for m in members:
            log.debug("\t\t\t[member " + m['id'] + "]: " + str(m))
            pool_map['members'][m['id']] = m

        log.debug("\t\t[vips]")
        vips = lb_client.get_vips(
            ctx,
            filters={'pool_id': [p['id']]})
        for vip in vips:
            log.debug("\t\t\t[vip " + vip['id'] + "]: " + str(vip))
            pool_map['vips'][vip['id']] = vip

    log.debug("\n[health_monitors]")
    health_monitors = [
        hm for hm in lb_client.get_health_monitors(ctx)
        if 'pools' in hm and len(hm['pools']) > 0]
    for hm in health_monitors:
        log.debug("\n\t[health-monitor " + hm['id'] + "]: " + str(hm))
        topology_map['health-monitors'][hm['id']] = hm

    log.debug("\n[security_groups]")
    security_groups = client.get_security_groups(ctx)
    for sg in security_groups:
        log.debug("\n\t[security-group " + sg['id'] + "]: " + str(sg))
        topology_map['security-groups'][sg['id']] = sg

    return topology_map


def migrate(debug=False, dry_run=False,
            from_version='v1.9.8'):

    if from_version != 'v1.9.8':
        raise ValueError('This script can only be run to migrate from '
                         'MidoNet version v1.9.8')

    log = logging.getLogger(name='data_migration/1.9.8')
    """:type: logging.Logger"""

    log.setLevel(level=logging.DEBUG if debug else logging.INFO)
    stdout_handler = logging.StreamHandler()
    stdout_handler.setLevel(level=logging.DEBUG if debug else logging.INFO)
    log.addHandler(stdout_handler)

    cfg.CONF(args=[], project='neutron')
    cfg.CONF.register_opts(config.core_opts)
    rpc.init(cfg.CONF)
    ctx = context.get_admin_context()

    old_topo = _prepare(log, ctx)

    log.info('Running migration process on topology')
    log.info('Topology to migrate:')
    log.info(pprint.pformat(old_topo, indent=1))

    networks = old_topo['networks']
    routers = old_topo['routers']
    fips = old_topo['floating-ips']
    pools = old_topo['load-balancer-pools']
    hms = old_topo['health-monitors']
    secgrps = old_topo['security-groups']

    task_transaction_list = []

    for nid, net in networks.iteritems():
        net_obj = net['network']
        subnets = net['subnets']
        ports = net['ports']
        log.debug("Creating network translation for: " + str(nid))
        task_transaction_list.append({'type': task.CREATE,
                                      'data_type': task.NETWORK,
                                      'resource_id': nid,
                                      'tenant_id': net_obj['tenant_id'],
                                      'data': net_obj})

        for sid, sub_obj in subnets.iteritems():
            log.debug("\tCreating subnet translation for: " + str(sid))
            task_transaction_list.append({'type': task.CREATE,
                                          'data_type': task.SUBNET,
                                          'resource_id': sid,
                                          'tenant_id': sub_obj['tenant_id'],
                                          'data': sub_obj})

        for pid, port_obj in ports.iteritems():
            log.debug("\tCreating port translation for: " + str(pid))
            task_transaction_list.append({'type': task.CREATE,
                                          'data_type': task.PORT,
                                          'resource_id': pid,
                                          'tenant_id': port_obj['tenant_id'],
                                          'data': port_obj})

    for rid, r in routers.iteritems():
        router_obj = r['router']
        """ :type: dict"""
        interfaces = r['interfaces']
        log.debug("Creating router translation for: " + str(rid))
        # Create a router with no routes and update them later
        route_obj = None
        if 'routes' in router_obj:
            route_obj = router_obj.pop('routes')

        task_transaction_list.append({'type': task.CREATE,
                                      'data_type': task.ROUTER,
                                      'resource_id': rid,
                                      'tenant_id': router_obj['tenant_id'],
                                      'data': router_obj})

        for iid, i in interfaces.iteritems():
            log.debug("\tCreating interface translation for: " + str(iid))
            if 'fixed_ips' not in i:
                raise upg.UpgradeScriptException(
                    'Router interface port has no fixed IPs:' + str(i))
            subnet_id = i['fixed_ips'][0]['subnet_id']
            interface_dict = {'id': rid,
                              'port_id': iid,
                              'subnet_id': subnet_id}
            task_transaction_list.append({'type': task.CREATE,
                                          'data_type': "ROUTERINTERFACE",
                                          'resource_id': rid,
                                          'tenant_id': router_obj['tenant_id'],
                                          'data': interface_dict})
        if route_obj:
            log.debug("\tAdding extra routes: " + str(route_obj))
            task_transaction_list.append({'type': task.UPDATE,
                                          'data_type': task.ROUTER,
                                          'resource_id': rid,
                                          'tenant_id': router_obj['tenant_id'],
                                          'data': route_obj})

    for fid, fip_obj in fips.iteritems():
        log.debug("Creating FIP translation for: " + str(fid))
        task_transaction_list.append({'type': task.CREATE,
                                      'data_type': task.FLOATING_IP,
                                      'resource_id': fid,
                                      'tenant_id': fip_obj['tenant_id'],
                                      'data': fip_obj})

    for pid, pool in pools.iteritems():
        pool_obj = pool['pool']
        vips = pool['vips']
        members = pool['members']
        log.debug("Creating LB pool translation for: " + str(pid))
        task_transaction_list.append({'type': task.CREATE,
                                      'data_type': task.POOL,
                                      'resource_id': pid,
                                      'tenant_id': pool_obj['tenant_id'],
                                      'data': pool_obj})

        for vid, vip_obj in vips.iteritems():
            log.debug("\tCreating LB VIP translation for: " + str(vip_obj))
            task_transaction_list.append({'type': task.CREATE,
                                          'data_type': task.VIP,
                                          'resource_id': vid,
                                          'tenant_id': vip_obj['tenant_id'],
                                          'data': vip_obj})

        for mid, member_obj in members.iteritems():
            log.debug("\tCreating LB member translation for: " + str(mid))
            task_transaction_list.append({'type': task.CREATE,
                                          'data_type': task.MEMBER,
                                          'resource_id': mid,
                                          'tenant_id': member_obj['tenant_id'],
                                          'data': member_obj})

    for hmid, hm_obj in hms.iteritems():
        log.debug("Creating health-monitor translation for: " + str(hmid))
        task_transaction_list.append({'type': task.CREATE,
                                      'data_type': task.HEALTH_MONITOR,
                                      'resource_id': hmid,
                                      'tenant_id': hm_obj['tenant_id'],
                                      'data': hm_obj})

    for sgid, sg_obj in secgrps.iteritems():
        log.debug("Creating security group translation for: " + str(sgid))
        task_transaction_list.append({'type': task.CREATE,
                                      'data_type': task.SECURITY_GROUP,
                                      'resource_id': sgid,
                                      'tenant_id': sg_obj['tenant_id'],
                                      'data': sg_obj})

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
