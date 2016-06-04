/*
 * Copyright 2015 Midokura SARL
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.midonet.cluster.rest_api

/**
 * This class contains the name of all the resources that are exposed via our
 * REST API.
 */
object ResourceUris {
    val ARP_TABLE: String = "arp_table"
    val BGP_NETWORKS: String = "bgp_networks"
    val BGP_PEERS: String = "bgp_peers"
    val BINDINGS: String = "bindings"
    val BRIDGES: String = "bridges"
    val CHAINS: String = "chains"
    val DHCP: String = "dhcp"
    val DHCPV6: String = "dhcpV6"
    val DHCPV6_HOSTS: String = "hostsV6"
    val DHCP_HOSTS: String = "hosts"
    val HEALTH_MONITORS: String = "health_monitors"
    val HOSTS: String = "hosts"
    val INTERFACES: String = "interfaces"
    val IP_ADDRS: String = "ip_addrs"
    val IP_ADDR_GROUPS: String = "ip_addr_groups"
    val LINK: String = "link"
    val LOAD_BALANCERS: String = "load_balancers"
    val L2INSERTIONS: String = "l2insertions"
    val MAC_TABLE: String = "mac_table"
    val MIRRORS: String = "mirrors"
    val PEER_PORTS: String = "peer_ports"
    val PEERING_TABLE: String = "peering_table"
    val POOLS: String = "pools"
    val POOL_MEMBERS: String = "pool_members"
    val PORTS: String = "ports"
    val PORT_GROUPS: String = "port_groups"
    val ROUTERS: String = "routers"
    val ROUTES: String = "routes"
    val RULES: String = "rules"
    val SYSTEM_STATE: String = "system_state"
    val SERVICE_CONTAINERS: String = "service_containers"
    val SERVICE_CONTAINER_GROUPS: String = "service_container_groups"
    val TENANTS: String = "tenants"
    val TRACE_REQUESTS: String = "traces"
    val TUNNEL_ZONES: String = "tunnel_zones"
    val VERSIONS: String = "versions"
    val VIPS: String = "vips"
    val VLANS: String = "vlans"
    val VTEPS: String = "vteps"
    val VTEP_BINDINGS: String = "vtep_bindings"
    val VXLAN_PORTS: String = "vxlan_ports"
    val TENANT_ID_PARAM: String = "tenant_id"

    def macToUri(mac: String): String = {
        mac.replace(':', '-')
    }

}
