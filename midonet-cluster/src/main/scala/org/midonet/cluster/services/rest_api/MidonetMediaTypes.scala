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

package org.midonet.cluster.services.rest_api

/** All the MediaTypes offered by MidoNet, plus some utility lookups into maps
  * between domains. */
object MidonetMediaTypes {

    final val APPLICATION_JSON_V5 = "application/vnd.org.midonet.Application-v5+json"

    final val APPLICATION_ERROR_JSON = "application/vnd.org.midonet.Error-v1+json"
    final val APPLICATION_TENANT_JSON_V2 = "application/vnd.org.midonet.Tenant-v2+json"
    final val APPLICATION_TENANT_COLLECTION_JSON_V2 = "application/vnd.org.midonet.collection.Tenant-v2+json"
    final val APPLICATION_ROUTER_JSON_V3 = "application/vnd.org.midonet.Router-v3+json"
    final val APPLICATION_ROUTER_COLLECTION_JSON_V3 = "application/vnd.org.midonet.collection.Router-v3+json"
    final val APPLICATION_BRIDGE_JSON_V4 = "application/vnd.org.midonet.Bridge-v4+json"
    final val APPLICATION_BRIDGE_COLLECTION_JSON_V4 = "application/vnd.org.midonet.collection.Bridge-v4+json"
    final val APPLICATION_L2INSERTION_JSON = "application/vnd.org.midonet.L2Insertion-v1+json"
    final val APPLICATION_L2INSERTION_COLLECTION_JSON = "application/vnd.org.midonet.collection.L2Insertion-v1+json"
    final val APPLICATION_L2SERVICE_JSON = "application/vnd.org.midonet.L2Service-v1+json"
    final val APPLICATION_L2SERVICE_COLLECTION_JSON = "application/vnd.org.midonet.collection.L2Service-v1+json"
    final val APPLICATION_MAC_PORT_JSON_V2 = "application/vnd.org.midonet.MacPort-v2+json"
    final val APPLICATION_MAC_PORT_COLLECTION_JSON_V2 = "application/vnd.org.midonet.collection.MacPort-v2+json"
    final val APPLICATION_IP4_MAC_JSON = "application/vnd.org.midonet.IP4Mac-v1+json"
    final val APPLICATION_IP4_MAC_COLLECTION_JSON = "application/vnd.org.midonet.collection.IP4Mac-v1+json"
    final val APPLICATION_HOST_JSON_V3 = "application/vnd.org.midonet.Host-v3+json"
    final val APPLICATION_HOST_COLLECTION_JSON_V3 = "application/vnd.org.midonet.collection.Host-v3+json"
    final val APPLICATION_INTERFACE_JSON = "application/vnd.org.midonet.Interface-v1+json"
    final val APPLICATION_INTERFACE_COLLECTION_JSON = "application/vnd.org.midonet.collection.Interface-v1+json"
    final val APPLICATION_PORT_V3_JSON = "application/vnd.org.midonet.Port-v3+json"
    final val APPLICATION_PORT_V3_COLLECTION_JSON = "application/vnd.org.midonet.collection.Port-v3+json"
    final val APPLICATION_PORT_LINK_JSON = "application/vnd.org.midonet.PortLink-v1+json"
    final val APPLICATION_ROUTE_JSON = "application/vnd.org.midonet.Route-v1+json"
    final val APPLICATION_ROUTE_COLLECTION_JSON = "application/vnd.org.midonet.collection.Route-v1+json"
    final val APPLICATION_PORTGROUP_JSON = "application/vnd.org.midonet.PortGroup-v1+json"
    final val APPLICATION_PORTGROUP_COLLECTION_JSON = "application/vnd.org.midonet.collection.PortGroup-v1+json"
    final val APPLICATION_PORTGROUP_PORT_JSON = "application/vnd.org.midonet.PortGroupPort-v1+json"
    final val APPLICATION_PORTGROUP_PORT_COLLECTION_JSON = "application/vnd.org.midonet.collection.PortGroupPort-v1+json"
    final val APPLICATION_CHAIN_JSON = "application/vnd.org.midonet.Chain-v1+json"
    final val APPLICATION_CHAIN_COLLECTION_JSON = "application/vnd.org.midonet.collection.Chain-v1+json"
    final val APPLICATION_RULE_JSON_V2 = "application/vnd.org.midonet.Rule-v2+json"
    final val APPLICATION_RULE_COLLECTION_JSON_V2 = "application/vnd.org.midonet.collection.Rule-v2+json"
    final val APPLICATION_BGP_NETWORK_JSON = "application/vnd.org.midonet.BgpNetwork-v1+json"
    final val APPLICATION_BGP_NETWORK_COLLECTION_JSON = "application/vnd.org.midonet.collection.BgpNetwork-v1+json"
    final val APPLICATION_BGP_PEER_JSON = "application/vnd.org.midonet.BgpPeer-v1+json"
    final val APPLICATION_BGP_PEER_COLLECTION_JSON = "application/vnd.org.midonet.collection.BgpPeer-v1+json"
    final val APPLICATION_MIRROR_JSON = "application/vnd.org.midonet.Mirror-v1+json"
    final val APPLICATION_MIRROR_COLLECTION_JSON = "application/vnd.org.midonet.collection.Mirror-v1+json"

    /* DHCP configuration types. */
    final val APPLICATION_DHCP_SUBNET_JSON_V2 = "application/vnd.org.midonet.DhcpSubnet-v2+json"
    final val APPLICATION_DHCP_SUBNET_COLLECTION_JSON_V2 = "application/vnd.org.midonet.collection.DhcpSubnet-v2+json"
    final val APPLICATION_DHCP_HOST_JSON_V2 = "application/vnd.org.midonet.DhcpHost-v2+json"
    final val APPLICATION_DHCP_HOST_COLLECTION_JSON_V2 = "application/vnd.org.midonet.collection.DhcpHost-v2+json"
    final val APPLICATION_DHCPV6_SUBNET_JSON = "application/vnd.org.midonet.DhcpV6Subnet-v1+json"
    final val APPLICATION_DHCPV6_SUBNET_COLLECTION_JSON = "application/vnd.org.midonet.collection.DhcpV6Subnet-v1+json"
    final val APPLICATION_DHCPV6_HOST_JSON = "application/vnd.org.midonet.DhcpV6Host-v1+json"
    final val APPLICATION_DHCPV6_HOST_COLLECTION_JSON = "application/vnd.org.midonet.collection.DhcpV6Host-v1+json"

    // Tunnel Zones
    final val APPLICATION_TUNNEL_ZONE_JSON = "application/vnd.org.midonet.TunnelZone-v1+json"
    final val APPLICATION_TUNNEL_ZONE_COLLECTION_JSON = "application/vnd.org.midonet.collection.TunnelZone-v1+json"

    final val APPLICATION_TUNNEL_ZONE_HOST_JSON = "application/vnd.org.midonet.TunnelZoneHost-v1+json"
    final val APPLICATION_TUNNEL_ZONE_HOST_COLLECTION_JSON = "application/vnd.org.midonet.collection.TunnelZoneHost-v1+json"
    final val APPLICATION_GRE_TUNNEL_ZONE_HOST_JSON = "application/vnd.org.midonet.GreTunnelZoneHost-v1+json"
    final val APPLICATION_GRE_TUNNEL_ZONE_HOST_COLLECTION_JSON = "application/vnd.org.midonet.collection.GreTunnelZoneHost-v1+json"

    // Host interface - port mapping
    final val APPLICATION_HOST_INTERFACE_PORT_JSON = "application/vnd.org.midonet.HostInterfacePort-v1+json"
    final val APPLICATION_HOST_INTERFACE_PORT_COLLECTION_JSON = "application/vnd.org.midonet.collection.HostInterfacePort-v1+json"

    // Upgrade Control
    final val APPLICATION_WRITE_VERSION_JSON = "application/vnd.org.midonet.WriteVersion-v1+json"
    final val APPLICATION_SYSTEM_STATE_JSON_V2 = "application/vnd.org.midonet.SystemState-v2+json"
    final val APPLICATION_HOST_VERSION_JSON = "application/vnd.org.midonet.HostVersion-v1+json"

    // L4LB
    final val APPLICATION_HEALTH_MONITOR_JSON = "application/vnd.org.midonet.HealthMonitor-v1+json"
    final val APPLICATION_HEALTH_MONITOR_COLLECTION_JSON = "application/vnd.org.midonet.collection.HealthMonitor-v1+json"
    final val APPLICATION_LOAD_BALANCER_JSON = "application/vnd.org.midonet.LoadBalancer-v1+json"
    final val APPLICATION_LOAD_BALANCER_COLLECTION_JSON = "application/vnd.org.midonet.collection.LoadBalancer-v1+json"
    final val APPLICATION_POOL_MEMBER_JSON = "application/vnd.org.midonet.PoolMember-v1+json"
    final val APPLICATION_POOL_MEMBER_COLLECTION_JSON = "application/vnd.org.midonet.collection.PoolMember-v1+json"
    final val APPLICATION_POOL_JSON = "application/vnd.org.midonet.Pool-v1+json"
    final val APPLICATION_POOL_COLLECTION_JSON = "application/vnd.org.midonet.collection.Pool-v1+json"
    final val APPLICATION_VIP_JSON = "application/vnd.org.midonet.VIP-v1+json"
    final val APPLICATION_VIP_COLLECTION_JSON = "application/vnd.org.midonet.collection.VIP-v1+json"

    // VXGW
    final val APPLICATION_VTEP_JSON_V2 = "application/vnd.org.midonet.VTEP-v2+json"
    final val APPLICATION_VTEP_COLLECTION_JSON_V2 = "application/vnd.org.midonet.collection.VTEP-v2+json"
    final val APPLICATION_VTEP_BINDING_JSON_V2 = "application/vnd.org.midonet.VTEPBinding-v2+json"
    final val APPLICATION_VTEP_BINDING_COLLECTION_JSON_V2 = "application/vnd.org.midonet.collection.VTEPBinding-v2+json"
    final val APPLICATION_VTEP_PORT_JSON = "application/vnd.org.midonet.VTEPPort-v1+json"
    final val APPLICATION_VTEP_PORT_COLLECTION_JSON = "application/vnd.org.midonet.collection.VTEPPort-v1+json"

    // Token Information
    final val APPLICATION_TOKEN_JSON = "application/vnd.org.midonet.Token-v1+json"

    final val APPLICATION_IP_ADDR_GROUP_JSON = "application/vnd.org.midonet.IpAddrGroup-v1+json"
    final val APPLICATION_IP_ADDR_GROUP_COLLECTION_JSON = "application/vnd.org.midonet.collection.IpAddrGroup-v1+json"

    final val APPLICATION_IP_ADDR_GROUP_ADDR_JSON = "application/vnd.org.midonet.IpAddrGroupAddr-v1+json"
    final val APPLICATION_IP_ADDR_GROUP_ADDR_COLLECTION_JSON = "application/vnd.org.midonet.collection.IpAddrGroupAddr-v1+json"

    final val APPLICATION_TRACE_REQUEST_JSON = "application/vnd.org.midonet.TraceRequest-v1+json"
    final val APPLICATION_TRACE_REQUEST_COLLECTION_JSON = "application/vnd.org.midonet.collection.TraceRequest-v1+json"

}
