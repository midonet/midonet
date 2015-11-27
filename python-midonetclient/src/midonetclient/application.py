# vim: tabstop=4 shiftwidth=4 softtabstop=4

# Copyright 2013 Midokura PTE LTD.
# All Rights Reserved
#
# Licensed under the Apache License, Version 2.0 (the "License"); you may
# not use this file except in compliance with the License. You may obtain
# a copy of the License at
#
# http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
# WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
# License for the specific language governing permissions and limitations
# under the License.

from midonetclient import bgp_network
from midonetclient import bgp_peer
from midonetclient import bridge
from midonetclient import mirror
from midonetclient import chain
from midonetclient import health_monitor
from midonetclient import host
from midonetclient import ip_addr_group
from midonetclient import load_balancer
from midonetclient import l2insertion
from midonetclient import pool
from midonetclient import pool_member
from midonetclient import pool_statistic
from midonetclient import port
from midonetclient import port_group
from midonetclient import resource_base
from midonetclient import route
from midonetclient import router
from midonetclient import rule
from midonetclient import service_container
from midonetclient import system_state
from midonetclient import tenant
from midonetclient import tunnel_zone
from midonetclient import tracerequest
from midonetclient import vendor_media_type
from midonetclient import vip
from midonetclient import vtep

import uuid

class Application(resource_base.ResourceBase):

    media_type = vendor_media_type.APPLICATION_JSON_V5
    ID_TOKEN = '{id}'
    IP_ADDR_TOKEN = '{ipAddr}'

    def __init__(self, uri, dto, auth):
        super(Application, self).__init__(uri, dto, auth)

    def get_bgp_network_template(self):
        return self.dto['bgpNetworkTemplate']

    def get_bgp_peer_template(self):
        return self.dto['bgpPeerTemplate']

    def get_bridge_template(self):
        return self.dto['bridgeTemplate']

    def get_mirror_template(self):
        return self.dto['mirrorTemplate']

    def get_chain_template(self):
        return self.dto['chainTemplate']

    def get_l2insertion_template(self):
        return self.dto['l2InsertionTemplate']

    def get_host_template(self):
        return self.dto['hostTemplate']

    def get_port_group_template(self):
        return self.dto['portGroupTemplate']

    def get_ip_addr_group_template(self):
        return self.dto['ipAddrGroupTemplate']

    def get_port_template(self):
        return self.dto['portTemplate']

    def get_route_template(self):
        return self.dto['routeTemplate']

    def get_router_template(self):
        return self.dto['routerTemplate']

    def get_rule_template(self):
        return self.dto['ruleTemplate']

    def get_service_container_template(self):
        return self.dto['serviceContainerTemplate']

    def get_tenant_template(self):
        return self.dto['tenantTemplate']

    def get_tunnel_zone_template(self):
        return self.dto['tunnelZoneTemplate']

    def get_vtep_template(self):
        return self.dto['vtepTemplate']

    def get_write_version_uri(self):
        return self.dto['writeVersion']

    def get_system_state_uri(self):
        return self.dto['systemState']

    #L4LB resources
    def get_load_balancers_uri(self):
        return self.dto['loadBalancers']

    def get_vips_uri(self):
        return self.dto['vips']

    def get_pools_uri(self):
        return self.dto['pools']

    def get_pool_members_uri(self):
        return self.dto['poolMembers']

    def get_ports_uri(self):
        return self.dto['ports']

    def get_health_monitors_uri(self):
        return self.dto['healthMonitors']

    def get_pool_statistics_uri(self):
        return self.dto['poolStatistics']

    def get_load_balancer_template(self):
        return self.dto['loadBalancerTemplate']

    def get_vip_template(self):
        return self.dto['vipTemplate']

    def get_pool_template(self):
        return self.dto['poolTemplate']

    def get_pool_member_template(self):
        return self.dto['poolMemberTemplate']

    def get_health_monitor_template(self):
        return self.dto['healthMonitorTemplate']

    def get_pool_statistic_template(self):
        return self.dto['poolStatisticTemplate']

    def get_service_container(self, id_):
        return self._get_resource_by_id(service_container.ServiceContainer, None,
                                        self.get_service_container_template(),
                                        id_)

    def get_service_containers(self, query):
        headers = {'Accept':
                   vendor_media_type.APPLICATION_SERVICE_CONTAINER_COLLECTION_JSON}
        return self.get_children(self.dto['service_containers'], query, headers,
                                 service_container.ServiceContainer)

    def get_tracerequest_template(self):
        return self.dto['traceRequestTemplate']

    def get_tenants(self, query):
        headers = {'Accept':
                   vendor_media_type.APPLICATION_TENANT_COLLECTION_JSON}
        return self.get_children(self.dto['tenants'], query, headers,
                                 tenant.Tenant)

    def get_routers(self, query):
        headers = {'Accept':
                   vendor_media_type.APPLICATION_ROUTER_COLLECTION_JSON}
        return self.get_children(self.dto['routers'], query, headers,
                                 router.Router)

    def get_bridges(self, query):
        headers = {'Accept':
                   vendor_media_type.APPLICATION_BRIDGE_COLLECTION_JSON}
        return self.get_children(self.dto['bridges'], query, headers,
                                 bridge.Bridge)

    def get_mirrors(self, query):
        headers = {'Accept':
                   vendor_media_type.APPLICATION_MIRROR_COLLECTION_JSON}
        return self.get_children(self.dto['mirrors'], query, headers,
                                 mirror.Mirror)

    def get_ports(self, query):
        headers = {'Accept':
                   vendor_media_type.APPLICATION_PORT_COLLECTION_JSON}
        return self.get_children(self.dto['ports'], query, headers, port.Port)

    def get_port_groups(self, query):
        headers = {'Accept':
                   vendor_media_type.APPLICATION_PORTGROUP_COLLECTION_JSON}

        return self.get_children(self.dto['portGroups'], query, headers,
                                 port_group.PortGroup)

    def get_ip_addr_groups(self, query):
        headers = {'Accept':
                   vendor_media_type.APPLICATION_IP_ADDR_GROUP_COLLECTION_JSON}
        return self.get_children(self.dto['ipAddrGroups'], query, headers,
                                 ip_addr_group.IpAddrGroup)

    def get_chains(self, query):
        headers = {'Accept':
                   vendor_media_type.APPLICATION_CHAIN_COLLECTION_JSON}
        return self.get_children(self.dto['chains'], query, headers,
                                 chain.Chain)

    def get_tunnel_zones(self, query):
        headers = {'Accept':
                   vendor_media_type.APPLICATION_TUNNEL_ZONE_COLLECTION_JSON}
        return self.get_children(self.dto['tunnelZones'], query, headers,
                                 tunnel_zone.TunnelZone)

    def get_tunnel_zone(self, id_):
        return self._get_resource_by_id(tunnel_zone.TunnelZone,
                                        self.dto['tunnelZones'],
                                        self.get_tunnel_zone_template(), id_)

    def get_hosts(self, query):
        headers = {'Accept':
                   vendor_media_type.APPLICATION_HOST_COLLECTION_JSON}
        return self.get_children(self.dto['hosts'], query, headers, host.Host)

    def delete_bgp_network(self, id_):
        return self._delete_resource_by_id(self.get_bgp_network_template(), id_)

    def get_bgp_network(self, id_):
        return self._get_resource_by_id(bgp_network.BgpNetwork, None,
                                        self.get_bgp_network_template(), id_)

    def delete_bgp_peer(self, id_):
        return self._delete_resource_by_id(self.get_bgp_peer_template(), id_)

    def get_bgp_peer(self, id_):
        return self._get_resource_by_id(bgp_peer.BgpPeer, None,
                                        self.get_bgp_peer_template(), id_)

    def delete_bridge(self, id_):
        return self._delete_resource_by_id(self.get_bridge_template(), id_)

    def delete_mirror(self, id_):
        return self._delete_resource_by_id(self.get_mirror_template(), id_)

    def get_bridge(self, id_):
        return self._get_resource_by_id(bridge.Bridge, self.dto['bridges'],
                                        self.get_bridge_template(), id_)

    def get_mirror(self, id_):
        return self._get_resource_by_id(mirror.Mirror, self.dto['mirrors'],
                                        self.get_mirror_template(), id_)
    def delete_chain(self, id_):
        return self._delete_resource_by_id(self.get_chain_template(), id_)

    def get_chain(self, id_):
        return self._get_resource_by_id(chain.Chain, self.dto['chains'],
                                        self.get_chain_template(), id_)

    def get_host(self, id_):
        return self._get_resource_by_id(host.Host, self.dto['hosts'],
                                        self.get_host_template(), id_)

    def delete_port_group(self, id_):
        return self._delete_resource_by_id(self.get_port_group_template(), id_)

    def get_port_group(self, id_):
        return self._get_resource_by_id(port_group.PortGroup,
                                        self.dto['portGroups'],
                                        self.get_port_group_template(), id_)

    def delete_ip_addr_group(self, id_):
        return self._delete_resource_by_id(self.get_ip_addr_group_template(),
                                           id_)

    def get_ip_addr_group(self, id_):
        return self._get_resource_by_id(ip_addr_group.IpAddrGroup,
                                        self.dto['ipAddrGroups'],
                                        self.get_ip_addr_group_template(), id_)

    def delete_port(self, id_):
        return self._delete_resource_by_id(self.get_port_template(), id_)

    def get_port(self, id_):
        return self._get_resource_by_id(port.Port, None,
                                        self.get_port_template(), id_)

    def delete_route(self, id_):
        return self._delete_resource_by_id(self.get_route_template(), id_)

    def get_route(self, id_):
        return self._get_resource_by_id(route.Route, None,
                                        self.get_route_template(), id_)

    def delete_l2insertion(self, id_):
        return self._delete_resource_by_id(self.get_l2insertion_template(), id_)

    def get_l2insertion(self, id_):
        return self._get_resource_by_id(l2insertion.L2Insertion, self.dto['l2insertions'],
                                        self.get_l2insertion_template(), id_)

    def get_l2insertions(self, query):
        headers = {'Accept':
                   vendor_media_type.APPLICATION_L2INSERTION_COLLECTION_JSON}
        return self.get_children(self.dto['l2insertions'], query, headers,
                                 l2insertion.L2Insertion)

    def add_l2insertion(self):
        return l2insertion.L2Insertion(self.dto['l2insertions'], {}, self.auth)

    def delete_router(self, id_):
        return self._delete_resource_by_id(self.get_router_template(), id_)

    def get_router(self, id_):
        return self._get_resource_by_id(router.Router, self.dto['routers'],
                                        self.get_router_template(), id_)

    def delete_rule(self, id_):
        return self._delete_resource_by_id(self.get_rule_template(), id_)

    def get_rule(self, id_):
        return self._get_resource_by_id(rule.Rule, None,
                                        self.get_rule_template(), id_)

    def get_tenant(self, id_):
        return self._get_resource_by_id(tenant.Tenant, self.dto['tenants'],
                                        self.get_tenant_template(), id_)

    def add_router(self):
        return router.Router(self.dto['routers'], {}, self.auth)

    def add_bridge(self):
        return bridge.Bridge(self.dto['bridges'], {}, self.auth)

    def add_mirror(self):
        return mirror.Mirror(self.dto['mirrors'], {}, self.auth)

    def add_port_group(self):
        return port_group.PortGroup(self.dto['portGroups'], {}, self.auth)

    def add_ip_addr_group(self):
        return ip_addr_group.IpAddrGroup(self.dto['ipAddrGroups'], {},
                                         self.auth)

    def add_chain(self):
        return chain.Chain(self.dto['chains'], {}, self.auth)

    def add_service_container(self):
        return service_container.ServiceContainer(self.dto['pools'], {},
                                                  self.auth)

    def add_tunnel_zone(self):
        return tunnel_zone.TunnelZone(self.dto['tunnelZones'], {}, self.auth)

    def add_gre_tunnel_zone(self):
        return tunnel_zone.TunnelZone(
            self.dto['tunnelZones'], {'type': 'gre'}, self.auth,
            vendor_media_type.APPLICATION_GRE_TUNNEL_ZONE_HOST_JSON,
            vendor_media_type.APPLICATION_GRE_TUNNEL_ZONE_HOST_COLLECTION_JSON)

    def add_vxlan_tunnel_zone(self):
        return tunnel_zone.TunnelZone(
            self.dto['tunnelZones'], {'type': 'vxlan'}, self.auth,
            vendor_media_type.APPLICATION_TUNNEL_ZONE_HOST_JSON,
            vendor_media_type.APPLICATION_TUNNEL_ZONE_HOST_COLLECTION_JSON)

    def add_vtep_tunnel_zone(self):
        return tunnel_zone.TunnelZone(
            self.dto['tunnelZones'], {'type': 'vtep'}, self.auth,
            vendor_media_type.APPLICATION_TUNNEL_ZONE_HOST_JSON,
            vendor_media_type.APPLICATION_TUNNEL_ZONE_HOST_COLLECTION_JSON)

    def get_system_state(self):
        return self._get_resource(system_state.SystemState, None,
                                  self.get_system_state_uri())

    def _create_uri_from_template(self, template, token, value):
        return template.replace(token, value)

    def _get_resource(self, clazz, create_uri, uri):
        return clazz(create_uri, {'uri': uri}, self.auth).get(
            headers={'Content-Type': clazz.media_type,
                     'Accept': clazz.media_type})

    def _get_resource_by_id(self, clazz, create_uri,
                            template, id_):
        uri = self._create_uri_from_template(template,
                                             self.ID_TOKEN,
                                             id_)
        return self._get_resource(clazz, create_uri, uri)

    def _get_resource_by_ip_addr(self, clazz, create_uri,
                                 template, ip_address):
        uri = self._create_uri_from_template(template,
                                             self.IP_ADDR_TOKEN,
                                             ip_address)
        return self._get_resource(clazz, create_uri, uri)

    def _delete_resource_by_id(self, template, id_):
        uri = self._create_uri_from_template(template,
                                             self.ID_TOKEN,
                                             id_)
        self.auth.do_request(uri, 'DELETE')

    def _delete_resource_by_ip_addr(self, template, ip_address):
        uri = self._create_uri_from_template(template,
                                             self.IP_ADDR_TOKEN,
                                             ip_address)
        self.auth.do_request(uri, 'DELETE')

    def _upload_resource(self, clazz, create_uri, uri, body, headers):
        return clazz(create_uri, {'uri': uri}, self.auth)\
            .upload(create_uri, body, headers=headers)

    #L4LB resources
    def get_load_balancers(self, query):
        headers = {'Accept':
                   vendor_media_type.APPLICATION_LOAD_BALANCER_COLLECTION_JSON}
        return self.get_children(self.dto['loadBalancers'],
                                 query, headers, load_balancer.LoadBalancer)

    def get_vips(self, query):
        headers = {'Accept':
                   vendor_media_type.APPLICATION_VIP_COLLECTION_JSON}
        return self.get_children(self.dto['vips'], query, headers, vip.VIP)

    def get_pools(self, query):
        headers = {'Accept':
                   vendor_media_type.APPLICATION_POOL_COLLECTION_JSON}
        return self.get_children(self.dto['pools'], query, headers, pool.Pool)

    def get_pool_members(self, query):
        headers = {'Accept':
                   vendor_media_type.APPLICATION_POOL_MEMBER_COLLECTION_JSON}
        return self.get_children(self.dto['poolMembers'],
                                 query, headers, pool_member.PoolMember)

    def get_health_monitors(self, query):
        headers = {'Accept':
            vendor_media_type.APPLICATION_HEALTH_MONITOR_COLLECTION_JSON}
        return self.get_children(self.dto['healthMonitors'],
                                 query, headers, health_monitor.HealthMonitor)

    def get_pool_statistics(self, query):
        headers = {'Accept':
            vendor_media_type.APPLICATION_POOL_STATISTIC_COLLECTION_JSON}
        return self.get_children(self.dto['poolStatistics'],
                                 query, headers, pool_statistic.PoolStatistic)

    def get_load_balancer(self, id_):
        return self._get_resource_by_id(load_balancer.LoadBalancer,
                                        self.dto['loadBalancers'],
                                        self.get_load_balancer_template(),
                                        id_)

    def get_vip(self, id_):
        return self._get_resource_by_id(vip.VIP,
                                        self.dto['vips'],
                                        self.get_vip_template(),
                                        id_)

    def get_pool(self, id_):
        return self._get_resource_by_id(pool.Pool,
                                        self.dto['pools'],
                                        self.get_pool_template(),
                                        id_)

    def get_pool_member(self, id_):
        return self._get_resource_by_id(pool_member.PoolMember,
                                        self.dto['poolMembers'],
                                        self.get_pool_member_template(),
                                        id_)

    def get_health_monitor(self, id_):
        return self._get_resource_by_id(health_monitor.HealthMonitor,
                                        self.dto['healthMonitors'],
                                        self.get_health_monitor_template(),
                                        id_)

    def get_pool_statistic(self, id_):
        return self._get_resource_by_id(pool_statistic.PoolStatistic,
                                        self.dto['poolStatistic'],
                                        self.get_pool_statistic_template(),
                                        id_)

    def delete_load_balancer(self, id_):
        return self._delete_resource_by_id(
            self.get_load_balancer_template(), id_)

    def delete_vip(self, id_):
        return self._delete_resource_by_id(self.get_vip_template(), id_)

    def delete_pool(self, id_):
        return self._delete_resource_by_id(self.get_pool_template(), id_)

    def delete_pool_member(self, id_):
        return self._delete_resource_by_id(
            self.get_pool_member_template(), id_)

    def delete_health_monitor(self, id_):
        return self._delete_resource_by_id(
            self.get_health_monitor_template(), id_)

    def delete_pool_statistic(self, id_):
        return self._delete_resource_by_id(
            self.get_pool_statistic_template(), id_)

    def add_load_balancer(self):
        return load_balancer.LoadBalancer(self.dto['loadBalancers'], {},
                                          self.auth)

    def add_vip(self):
        return vip.VIP(self.dto['vips'], {}, self.auth)

    def add_pool(self):
        return pool.Pool(self.dto['pools'], {}, self.auth)

    def add_pool_member(self):
        return pool_member.PoolMember(self.dto['poolMembers'], {}, self.auth)

    def add_health_monitor(self):
        return health_monitor.HealthMonitor(self.dto['healthMonitors'], {},
                                            self.auth)

    def add_pool_statistic(self):
        return pool_statistic.PoolStatistic(self.dto['poolStatistics'], {},
                                            self.auth)

    def get_vteps(self):
        headers = {'Accept':
                   vendor_media_type.APPLICATION_VTEP_COLLECTION_JSON_V2}
        return self.get_children(self.dto['vteps'], {}, headers, vtep.Vtep)

    def add_vtep(self):
        return vtep.Vtep(self.dto['vteps'], {}, self.auth)

    def get_vtep(self, id):
        return self._get_resource_by_id(vtep.Vtep,
                                        self.dto['vteps'],
                                        self.get_vtep_template(),
                                        id)

    def delete_vtep(self, id):
        return self._delete_resource_by_id(self.get_vtep_template(),
                                           id)

    def get_tracerequests(self, query):
        headers = {'Accept':
                  vendor_media_type.APPLICATION_TRACE_REQUEST_COLLECTION_JSON}
        return self.get_children(self.dto['traceRequests'], query, headers,
                                 tracerequest.TraceRequest)

    def add_tracerequest(self):
        return tracerequest.TraceRequest(self.dto['traceRequests'],
                                         {"id" : str(uuid.uuid4())},
                                         self.auth)

    def get_tracerequest(self, _id):
        return self._get_resource_by_id(tracerequest.TraceRequest,
                                        self.dto['traceRequests'],
                                        self.get_tracerequest_template(),
                                        _id)

    def delete_tracerequest(self, _id):
        return self._delete_resource_by_id(self.get_tracerequest_template(),
                                           _id)
