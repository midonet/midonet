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

package org.midonet.brain.services.rest_api

import com.google.protobuf.Message

import org.midonet.brain.services.rest_api.models._
import org.midonet.cluster.data.ZoomObject
import org.midonet.cluster.models.Topology
import org.midonet.util.collection.Bimap

/** All the MediaTypes offered by MidoNet */
object MidonetMediaTypes {

    final val APPLICATION_JSON_V4 = "application/vnd.org.midonet.Application-v4+json"
    final val APPLICATION_JSON_V5 = "application/vnd.org.midonet.Application-v5+json"

    final val APPLICATION_ERROR_JSON = "application/vnd.org.midonet.Error-v1+json"
    final val APPLICATION_TENANT_JSON = "application/vnd.org.midonet.Tenant-v1+json"
    final val APPLICATION_TENANT_COLLECTION_JSON = "application/vnd.org.midonet.collection.Tenant-v1+json"
    final val APPLICATION_ROUTER_JSON_V2 = "application/vnd.org.midonet.Router-v2+json"
    final val APPLICATION_ROUTER_JSON = "application/vnd.org.midonet.Router-v1+json"
    final val APPLICATION_ROUTER_COLLECTION_JSON = "application/vnd.org.midonet.collection.Router-v1+json"
    final val APPLICATION_ROUTER_COLLECTION_JSON_V2 = "application/vnd.org.midonet.collection.Router-v2+json"
    final val APPLICATION_BRIDGE_JSON = "application/vnd.org.midonet.Bridge-v1+json"
    final val APPLICATION_BRIDGE_JSON_V2 = "application/vnd.org.midonet.Bridge-v2+json"
    final val APPLICATION_BRIDGE_JSON_V3 = "application/vnd.org.midonet.Bridge-v3+json"
    final val APPLICATION_BRIDGE_COLLECTION_JSON = "application/vnd.org.midonet.collection.Bridge-v1+json"
    final val APPLICATION_BRIDGE_COLLECTION_JSON_V2 = "application/vnd.org.midonet.collection.Bridge-v2+json"
    final val APPLICATION_BRIDGE_COLLECTION_JSON_V3 = "application/vnd.org.midonet.collection.Bridge-v3+json"
    final val APPLICATION_MAC_PORT_JSON = "application/vnd.org.midonet.MacPort-v1+json"
    final val APPLICATION_MAC_PORT_JSON_V2 = "application/vnd.org.midonet.MacPort-v2+json"
    final val APPLICATION_MAC_PORT_COLLECTION_JSON = "application/vnd.org.midonet.collection.MacPort-v1+json"
    final val APPLICATION_MAC_PORT_COLLECTION_JSON_V2 = "application/vnd.org.midonet.collection.MacPort-v2+json"
    final val APPLICATION_IP4_MAC_JSON = "application/vnd.org.midonet.IP4Mac-v1+json"
    final val APPLICATION_IP4_MAC_COLLECTION_JSON = "application/vnd.org.midonet.collection.IP4Mac-v1+json"
    final val APPLICATION_HOST_JSON_V2 = "application/vnd.org.midonet.Host-v2+json"
    final val APPLICATION_HOST_JSON_V3 = "application/vnd.org.midonet.Host-v3+json"
    final val APPLICATION_HOST_COLLECTION_JSON_V2 = "application/vnd.org.midonet.collection.Host-v2+json"
    final val APPLICATION_HOST_COLLECTION_JSON_V3 = "application/vnd.org.midonet.collection.Host-v3+json"
    final val APPLICATION_INTERFACE_JSON = "application/vnd.org.midonet.Interface-v1+json"
    final val APPLICATION_INTERFACE_COLLECTION_JSON = "application/vnd.org.midonet.collection.Interface-v1+json"
    final val APPLICATION_PORT_JSON = "application/vnd.org.midonet.Port-v1+json"
    final val APPLICATION_PORT_COLLECTION_JSON = "application/vnd.org.midonet.collection.Port-v1+json"
    final val APPLICATION_PORT_V2_JSON = "application/vnd.org.midonet.Port-v2+json"
    final val APPLICATION_PORT_V2_COLLECTION_JSON = "application/vnd.org.midonet.collection.Port-v2+json"
    final val APPLICATION_PORT_LINK_JSON = "application/vnd.org.midonet.PortLink-v1+json"
    final val APPLICATION_ROUTE_JSON = "application/vnd.org.midonet.Route-v1+json"
    final val APPLICATION_ROUTE_COLLECTION_JSON = "application/vnd.org.midonet.collection.Route-v1+json"
    final val APPLICATION_PORTGROUP_JSON = "application/vnd.org.midonet.PortGroup-v1+json"
    final val APPLICATION_PORTGROUP_COLLECTION_JSON = "application/vnd.org.midonet.collection.PortGroup-v1+json"
    final val APPLICATION_PORTGROUP_PORT_JSON = "application/vnd.org.midonet.PortGroupPort-v1+json"
    final val APPLICATION_PORTGROUP_PORT_COLLECTION_JSON = "application/vnd.org.midonet.collection.PortGroupPort-v1+json"
    final val APPLICATION_CHAIN_JSON = "application/vnd.org.midonet.Chain-v1+json"
    final val APPLICATION_CHAIN_COLLECTION_JSON = "application/vnd.org.midonet.collection.Chain-v1+json"
    final val APPLICATION_RULE_JSON = "application/vnd.org.midonet.Rule-v1+json"
    final val APPLICATION_RULE_COLLECTION_JSON = "application/vnd.org.midonet.collection.Rule-v1+json"
    final val APPLICATION_RULE_JSON_V2 = "application/vnd.org.midonet.Rule-v2+json"
    final val APPLICATION_RULE_COLLECTION_JSON_V2 = "application/vnd.org.midonet.collection.Rule-v2+json"
    final val APPLICATION_BGP_JSON = "application/vnd.org.midonet.Bgp-v1+json"
    final val APPLICATION_BGP_COLLECTION_JSON = "application/vnd.org.midonet.collection.Bgp-v1+json"
    final val APPLICATION_AD_ROUTE_JSON = "application/vnd.org.midonet.AdRoute-v1+json"
    final val APPLICATION_AD_ROUTE_COLLECTION_JSON = "application/vnd.org.midonet.collection.AdRoute-v1+json"

    /* DHCP configuration types. */
    final val APPLICATION_DHCP_SUBNET_JSON = "application/vnd.org.midonet.DhcpSubnet-v1+json"
    final val APPLICATION_DHCP_SUBNET_JSON_V2 = "application/vnd.org.midonet.DhcpSubnet-v2+json"
    final val APPLICATION_DHCP_SUBNET_COLLECTION_JSON = "application/vnd.org.midonet.collection.DhcpSubnet-v1+json"
    final val APPLICATION_DHCP_SUBNET_COLLECTION_JSON_V2 = "application/vnd.org.midonet.collection.DhcpSubnet-v2+json"
    final val APPLICATION_DHCP_HOST_JSON = "application/vnd.org.midonet.DhcpHost-v1+json"
    final val APPLICATION_DHCP_HOST_COLLECTION_JSON = "application/vnd.org.midonet.collection.DhcpHost-v1+json"
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
    final val APPLICATION_SYSTEM_STATE_JSON = "application/vnd.org.midonet.SystemState-v1+json"
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
    final val APPLICATION_VTEP_JSON = "application/vnd.org.midonet.VTEP-v1+json"
    final val APPLICATION_VTEP_COLLECTION_JSON = "application/vnd.org.midonet.collection.VTEP-v1+json"
    final val APPLICATION_VTEP_BINDING_JSON = "application/vnd.org.midonet.VTEPBinding-v1+json"
    final val APPLICATION_VTEP_BINDING_COLLECTION_JSON = "application/vnd.org.midonet.collection.VTEPBinding-v1+json"
    final val APPLICATION_VTEP_PORT_COLLECTION_JSON = "application/vnd.org.midonet.collection.VTEPPort-v1+json"

    // Token Information
    final val APPLICATION_TOKEN_JSON = "application/vnd.org.midonet.Token-v1+json"

    final val APPLICATION_IP_ADDR_GROUP_JSON = "application/vnd.org.midonet.IpAddrGroup-v1+json"
    final val APPLICATION_IP_ADDR_GROUP_COLLECTION_JSON = "application/vnd.org.midonet.collection.IpAddrGroup-v1+json"

    final val APPLICATION_IP_ADDR_GROUP_ADDR_JSON = "application/vnd.org.midonet.IpAddrGroupAddr-v1+json"
    final val APPLICATION_IP_ADDR_GROUP_ADDR_COLLECTION_JSON = "application/vnd.org.midonet.collection.IpAddrGroupAddr-v1+json"

    final val APPLICATION_TRACE_REQUEST_JSON = "application/vnd.org.midonet.TraceRequest-v1+json"
    final val APPLICATION_TRACE_REQUEST_COLLECTION_JSON = "application/vnd.org.midonet.collection.TraceRequest-v1+json"

    val resourceOf = Map[String, Class[_ <: UriResource]] (
        APPLICATION_BRIDGE_COLLECTION_JSON -> classOf[Bridge],
        APPLICATION_BRIDGE_COLLECTION_JSON_V2 -> classOf[Bridge],
        APPLICATION_BRIDGE_COLLECTION_JSON_V3 -> classOf[Bridge],
        APPLICATION_BRIDGE_JSON -> classOf[Bridge],
        APPLICATION_BRIDGE_JSON_V2 -> classOf[Bridge],
        APPLICATION_BRIDGE_JSON_V3 -> classOf[Bridge],
        APPLICATION_HOST_COLLECTION_JSON_V2 -> classOf[Host],
        APPLICATION_HOST_COLLECTION_JSON_V3 -> classOf[Host],
        APPLICATION_HOST_JSON_V2 -> classOf[Host],
        APPLICATION_HOST_JSON_V3 -> classOf[Host],
        APPLICATION_PORT_COLLECTION_JSON -> classOf[Port],
        APPLICATION_PORT_JSON -> classOf[Port],
        APPLICATION_PORT_V2_COLLECTION_JSON-> classOf[Port],
        APPLICATION_PORT_V2_JSON-> classOf[Port],
        APPLICATION_ROUTER_COLLECTION_JSON -> classOf[Router],
        APPLICATION_ROUTER_COLLECTION_JSON_V2 -> classOf[Router],
        APPLICATION_ROUTER_JSON -> classOf[Router],
        APPLICATION_ROUTER_JSON_V2 -> classOf[Router],
        APPLICATION_TUNNEL_ZONE_COLLECTION_JSON -> classOf[TunnelZone],
        APPLICATION_TUNNEL_ZONE_JSON -> classOf[TunnelZone]
    )

    // TODO: This should be redundant, by looking at the key's annotations
    val zoomFor = Bimap(Map[Class[_ <: ZoomObject], Class[_ <: Message]] (
        classOf[BridgePort] -> classOf[Topology.Port],
        classOf[Bridge] -> classOf[Topology.Network],
        classOf[Host] -> classOf[Topology.Host],
        classOf[Port] -> classOf[Topology.Port],
        classOf[RouterPort] -> classOf[Topology.Port],
        classOf[Router] -> classOf[Topology.Router],
        classOf[TunnelZone] -> classOf[Topology.TunnelZone]
    ))

    import ResourceUris._
    val resourceNames = Bimap[String, String](Map(
        APPLICATION_BRIDGE_JSON -> BRIDGES,
        APPLICATION_BRIDGE_JSON_V2 -> BRIDGES,
        APPLICATION_BRIDGE_JSON_V3 -> BRIDGES,
        APPLICATION_HOST_JSON_V2 -> HOSTS,
        APPLICATION_HOST_JSON_V3 -> HOSTS,
        APPLICATION_PORT_JSON -> PORTS,
        APPLICATION_PORT_V2_JSON-> PORTS,
        APPLICATION_ROUTER_JSON -> ROUTERS,
        APPLICATION_ROUTER_JSON_V2 -> ROUTERS,
        APPLICATION_TUNNEL_ZONE_JSON -> TUNNEL_ZONES,
        // APPLICATION_ERROR_JSON -> ERRORS,
        APPLICATION_TENANT_JSON -> TENANTS,
        // APPLICATION_MAC_PORT_JSON -> MAC_PORTS,
        // APPLICATION_MAC_PORT_JSON_V2 -> MAC_PORTS,
        // APPLICATION_IP4_MAC_JSON ->
        // APPLICATION_INTERFACE_JSON ->
        APPLICATION_PORT_JSON -> PORTS,
        APPLICATION_PORT_V2_JSON -> PORTS,
        // APPLICATION_PORT_LINK_JSON ->
        APPLICATION_ROUTE_JSON -> ROUTES,
        APPLICATION_PORTGROUP_JSON -> PORT_GROUPS,
        APPLICATION_PORTGROUP_PORT_JSON -> PORT_GROUPS,
        APPLICATION_CHAIN_JSON -> CHAINS,
        APPLICATION_RULE_JSON -> RULES,
        APPLICATION_RULE_JSON_V2 -> RULES,
        APPLICATION_BGP_JSON -> BGP,
        APPLICATION_AD_ROUTE_JSON -> AD_ROUTES,
        APPLICATION_DHCP_SUBNET_JSON -> DHCP,
        APPLICATION_DHCP_SUBNET_JSON_V2 -> DHCP,
        APPLICATION_DHCP_HOST_JSON -> DHCP_HOSTS,
        APPLICATION_DHCP_HOST_JSON_V2 -> DHCP_HOSTS,
        APPLICATION_DHCPV6_SUBNET_JSON -> DHCPV6,
        APPLICATION_DHCPV6_HOST_JSON -> DHCPV6_HOSTS,
        APPLICATION_TUNNEL_ZONE_JSON -> TUNNEL_ZONES,
        APPLICATION_TUNNEL_ZONE_HOST_JSON -> TUNNEL_ZONES,
        // APPLICATION_GRE_TUNNEL_ZONE_HOST_JSON ->
        // APPLICATION_HOST_INTERFACE_PORT_JSON ->
        APPLICATION_WRITE_VERSION_JSON -> WRITE_VERSION,
        APPLICATION_SYSTEM_STATE_JSON -> SYSTEM_STATE,
        APPLICATION_SYSTEM_STATE_JSON_V2 -> SYSTEM_STATE,
        APPLICATION_HOST_VERSION_JSON -> HOSTS,
        APPLICATION_HEALTH_MONITOR_JSON -> HEALTH_MONITORS,
        APPLICATION_LOAD_BALANCER_JSON -> LOAD_BALANCERS,
        APPLICATION_POOL_MEMBER_JSON -> POOL_MEMBERS,
        APPLICATION_POOL_JSON -> POOLS,
        APPLICATION_VIP_JSON -> VIPS,
        APPLICATION_VTEP_JSON -> VTEPS,
        APPLICATION_VTEP_BINDING_JSON -> VTEP_BINDINGS,
        APPLICATION_IP_ADDR_GROUP_JSON -> IP_ADDR_GROUPS,
        APPLICATION_IP_ADDR_GROUP_ADDR_JSON -> IP_ADDR_GROUPS,
        APPLICATION_TRACE_REQUEST_JSON -> TRACE_REQUESTS
    ))

    def resourceFromName(resName: String): String =
        resourceNames.inverse.get(resName).orNull

    def protoFromResName(resName: String): Class[_ <: Message] = {
        zoomFor.get(resourceOf.get(resourceFromName(resName)).orNull).orNull
    }
}
