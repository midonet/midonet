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
from data_migration import context as ctx
import logging
from webob import exc as wexc

LOG = logging.getLogger(name="data_migration")
migration_context = ctx.migration_context


def _get_objects_by_path(path):
    return _get_objects_by_url(migration_context.mn_url + '/' + path + '/')


def _get_objects_by_url(url):
    return migration_context.mn_client.get(uri=url, media_type="*/*")


def _get_provider_router():
    routers = _get_objects_by_path('routers')
    provider_router = next(r for r in routers
                           if r['name'] == const.PROVIDER_ROUTER_NAME)
    LOG.debug("[(MIDONET) Provider Router]: " + str(provider_router))
    return provider_router


def _prepare_host_map(lb_pool_ids=None):
    LOG.debug("[(MIDONET) hosts]")
    host_map = {}
    if lb_pool_ids is None:
        lb_pool_ids = []
    hm_dp_ifaces = [i[0:8] + "_hm_dp" for i in lb_pool_ids]
    pr = _get_provider_router()

    hosts = _get_objects_by_path('hosts')
    for host in hosts if hosts else []:
        LOG.debug("\t[(MIDONET) host " + host['id'] + "]: " + str(host))
        host_map[host['id']] = {}
        h = host_map[host['id']]
        h['host'] = host
        h['ports'] = {}
        port_map = h['ports']

        # Skip ports for health monitors
        ports = _get_objects_by_path('hosts/' + host['id'] + "/ports")
        for port in [p for p in ports
                     if p['interfaceName'] not in hm_dp_ifaces]:
            port_obj = _get_objects_by_url(port['port'])

            # Skip port bindings for external routers (provider router device)
            if port_obj['deviceId'] != pr['id']:
                LOG.debug("\t\t[(MIDONET) port binding " +
                          port['interfaceName'] + "=" + port['portId'] + "]")
                port_map[port['interfaceName']] = port['portId']
    return host_map


def _prepare_tz_list():
    LOG.debug("[(MIDONET) tunnel zones]")
    tz_list = []
    tzs = _get_objects_by_path('tunnel_zones')
    for tz in tzs if tzs else []:
        LOG.debug("\t[(MIDONET) tz " + tz['id'] + "]: " + str(tz))
        hosts = _get_objects_by_path('tunnel_zones/' + tz['id'] + "/hosts")

        tz_map = {'tz': tz, 'hosts': {}}
        host_map = tz_map['hosts']

        for host in hosts:
            LOG.debug("\t\t[(MIDONET) tz host]: " + str(host))
            host_map[host['hostId']] = host

        tz_list.append(tz_map)
    return tz_list


def migrate(lb_pool_ids=None, dry_run=False):
    mn_map = dict()
    mn_map['hosts'] = _prepare_host_map(lb_pool_ids=lb_pool_ids)
    mn_map['tunnel_zones'] = _prepare_tz_list()

    for tz in mn_map['tunnel_zones'] if 'tunnel_zones' in mn_map else []:
        tz_obj = tz['tz']
        if dry_run:
            LOG.info("mn_api.add_tunnel_zone()type(" + tz_obj['type'] + ")"
                     ".name(" + tz_obj['name'] + ").create()")
            new_tz = None
        else:
            try:
                new_tz = (migration_context.mn_api.add_tunnel_zone()
                          .type(tz_obj['type'])
                          .name(tz_obj['name'])
                          .create())
            except wexc.HTTPClientError as e:
                if e.code == 409:
                    LOG.warn('Tunnel zone already exists: ' + tz_obj['name'])
                    tz_list = migration_context.mn_api.get_tunnel_zones()
                    new_tz = next(tz for tz in tz_list
                                  if tz.get_name() == tz_obj['name'])
                else:
                    raise e

        for host_id, host in tz['hosts'].iteritems():
            if dry_run:
                LOG.info("new_tz.add_tunnel_zone_host().ip_address(" +
                         host['ipAddress'] + ").host_id(" + host['hostId'] +
                         ").create()")
            else:
                (new_tz.add_tunnel_zone_host().ip_address(host['ipAddress'])
                 .host_id(host['hostId']).create())

    for host_id, host in (mn_map['hosts'].iteritems()
                          if 'hosts' in mn_map else []):
        host_obj = migration_context.mn_api.get_host(host_id)
        for iface, port in host['ports'].iteritems():
            if dry_run:
                LOG.info("mn_api.add_host_interface_port(host_obj,"
                         "port_id=" + port + ",interface_name=" + iface + ")")
            else:
                migration_context.mn_api.add_host_interface_port(
                    host_obj, port_id=port, interface_name=iface)
