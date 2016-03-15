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
from data_migration.migration_defs import midonet_post_command_file
from data_migration.migration_funcs import UpgradeScriptException

import logging

log = logging.getLogger(name='data_migration/1.9.8')
""":type: logging.Logger"""

cfg = MigrationConfig()


def _inspect_mn_objects(migrate_changed_obejcts=False):
    log.info("Inspecting all MN objects that were created " +
             ("or changed " if migrate_changed_obejcts else "") +
             "by the MidoNet CLI or API, bypassing the Neutron DB")

    log.info("Note: Neutron DB data will be unaffected. Only new "
             "5.0 MN data will be created (1.9 data will remain as backup).")
    log.info("Using MidoNet API: " + cfg.mn_url)

    topology_map = {
        "mn_hosts": {},
        "mn_tzs": {},
        "mn_bgps": {},
        "mn_vteps": [],
        "mn_ip_groups": {},
        "mn_lbs": {},
        "mn_port_groups": {}
    }

    log.debug("\t[(MIDONET) hosts]")

    hosts = cfg.mn_get_objects('hosts')
    for host in hosts if hosts else []:
        log.debug("\t[(MIDONET) host " + host['id'] + "]: " + str(host))
        topology_map['mn_hosts']['host'] = host
        topology_map['mn_hosts']['ports'] = {}
        port_map = topology_map['mn_hosts']['ports']

        ports = cfg.mn_get_objects('hosts/' + host['id'] + "/ports")
        for port in ports:
            log.debug("\t[(MIDONET) port binding " + port['interfaceName'] +
                      "=" + port['portId'] + "]")
            port_map[port['interfaceName']] = port['portId']

    tzs = cfg.mn_get_objects('tunnel_zones')
    for tz in tzs if tzs else []:
        log.debug("\t[(MIDONET) tz " + tz['id'] + "]: " + str(tz))
        topology_map['mn_tzs']['tz'] = tz
        topology_map['mn_tzs']['hosts'] = {}
        host_map = topology_map['mn_tzs']['hosts']

        hosts = cfg.mn_get_objects('tunnel_zones/' + tz['id'] + "/hosts")
        for host in hosts:
            log.debug("\t\t[(MIDONET) tz host]: " + str(host))
            host_map[host['hostId']] = host

    bgps = cfg.mn_get_objects('bgps')
    for bgp in bgps if bgps else []:
        log.debug("\t[(MIDONET) bgp " + bgp['id'] + "]: " + str(bgp))
        topology_map['mn_bgps'][bgp['id']] = bgp
        topology_map['mv_ip_groups']['ip_group'] = bgp
        topology_map['mv_ip_groups']['addrs'] = []
        addr_list = topology_map['mv_ip_groups']['addrs']

        addrs = cfg.mn_get_objects('ip_addr_groups/' + bgp['id'] + "/ip_addrs")
        for addr in addrs:
            log.debug("\t\t[(MIDONET) ip_addr_group addr " +
                      str(addr['addr']) + "]")
            addr_list.append(addr)

    # vteps = curl_get_json(api_url + '/vteps')
    # for vtep in vteps:
    #     log.debug("\t[(MIDONET) vtep " + vtep['id'] + "]: " + str(vtep))
    #     topology_map['mn_vteps'].append(vtep)
    #     topology_map['mv_ip_groups']['ip_group'] = ip_group
    #     topology_map['mv_ip_groups']['addrs'] = []
    #     addr_list = topology_map['mv_ip_groups']['addrs']
    #
    #     addrs = curl_get_json(api_url + "/" + 'ip_addr_groups/' +
    #                           ip_group['id'] + "/ip_addrs")
    #     for addr in addrs:
    #         log.debug("\t[(MIDONET) ip_addr_group addr " + addr['addr'] + "]")
    #         addr_list.append(addr)

    ip_groups = cfg.mn_get_objects('ip_addr_groups')
    for ip_group in ip_groups if ip_groups else[]:
        log.debug("\t[(MIDONET) ip_group " + ip_group['id'] + "]: " +
                  str(ip_group))
        topology_map['mn_ip_groups'][ip_group['id']] = ip_group
        topology_map['mn_ip_groups']['ip_group'] = ip_group
        topology_map['mn_ip_groups']['addrs'] = []
        addr_list = topology_map['mn_ip_groups']['addrs']

        addrs = cfg.mn_get_objects('ip_addr_groups/' +
                                   ip_group['id'] + "/ip_addrs")
        for addr in addrs:
            log.debug("\t\t[(MIDONET) ip_addr_group addr " + addr['addr'] + "]")
            addr_list.append(addr)

    lbs = cfg.mn_get_objects('load_balancers')
    for lb in lbs if lbs else[]:
        log.debug("\t[(MIDONET) lb " + lb['id'] + "]: " + str(lb))
        topology_map['mn_lbs'][lb['id']] = lb

    pgs = cfg.mn_get_objects('port_groups')
    for pg in pgs if pgs else[]:
        log.debug("\t[(MIDONET) pg " + pg['id'] + "]: " + str(pg))
        topology_map['mn_port_groups'][pg['id']] = pg

    return topology_map


def _inspect_changed_objects():
    topology_map = {}
    return topology_map


def migrate(debug=False,
            migrate_changed_obejcts=False,
            dry_run=False, from_version='v1.9.8'):

    if from_version != 'v1.9.8':
        raise ValueError('This script can only be run to migrate from '
                         'MidoNet version v1.9.8')

    log.setLevel(level=logging.DEBUG if debug else logging.INFO)
    stdout_handler = logging.StreamHandler()
    stdout_handler.setLevel(level=logging.DEBUG if debug else logging.INFO)
    log.addHandler(stdout_handler)

    _inspect_mn_objects(
        migrate_changed_obejcts=migrate_changed_obejcts)
