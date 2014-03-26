/*
 * Copyright 2011 Midokura KK
 * Copyright 2012 Midokura PTE LTD.
 */
package org.midonet.client;

/**
 * Vendor media types that represent resources available in MidoNet API.
 */
public class VendorMediaType {

    // IMPORTANT: There are two copies of this file, one in midonet-api and
    // one in midonet-client. When updating one, make sure to make the same
    // change in the other.

    public static final String APPLICATION_JSON_V2 =
            "application/vnd.org.midonet.Application-v2+json";
    public static final String APPLICATION_JSON_V3 =
            "application/vnd.org.midonet.Application-v3+json";

    public static final String APPLICATION_ERROR_JSON =
            "application/vnd.org.midonet.Error-v1+json";
    public static final String APPLICATION_TENANT_JSON =
            "application/vnd.org.midonet.Tenant-v1+json";
    public static final String APPLICATION_TENANT_COLLECTION_JSON =
            "application/vnd.org.midonet.collection.Tenant-v1+json";
    public static final String APPLICATION_ROUTER_JSON =
            "application/vnd.org.midonet.Router-v1+json";
    public static final String APPLICATION_ROUTER_JSON_V2 =
            "application/vnd.org.midonet.Router-v2+json";
    public static final String APPLICATION_ROUTER_COLLECTION_JSON =
            "application/vnd.org.midonet.collection.Router-v1+json";
    public static final String APPLICATION_ROUTER_COLLECTION_JSON_V2 =
            "application/vnd.org.midonet.collection.Router-v2+json";
    public static final String APPLICATION_BRIDGE_JSON =
            "application/vnd.org.midonet.Bridge-v1+json";
    public static final String APPLICATION_BRIDGE_COLLECTION_JSON =
            "application/vnd.org.midonet.collection.Bridge-v1+json";
    public static final String APPLICATION_MAC_PORT_JSON =
            "application/vnd.org.midonet.MacPort-v1+json";
    public static final String APPLICATION_MAC_PORT_JSON_V2 =
            "application/vnd.org.midonet.MacPort-v2+json";
    public static final String APPLICATION_MAC_PORT_COLLECTION_JSON =
            "application/vnd.org.midonet.collection.MacPort-v1+json";
    public static final String APPLICATION_MAC_PORT_COLLECTION_JSON_V2 =
            "application/vnd.org.midonet.collection.MacPort-v2+json";
    public static final String APPLICATION_IP4_MAC_JSON =
            "application/vnd.org.midonet.IP4Mac-v1+json";
    public static final String APPLICATION_IP4_MAC_COLLECTION_JSON =
            "application/vnd.org.midonet.collection.IP4Mac-v1+json";
    public static final String APPLICATION_HOST_JSON =
            "application/vnd.org.midonet.Host-v1+json";
    public static final String APPLICATION_HOST_COLLECTION_JSON =
            "application/vnd.org.midonet.collection.Host-v1+json";
    public static final String APPLICATION_INTERFACE_JSON =
            "application/vnd.org.midonet.Interface-v1+json";
    public static final String APPLICATION_INTERFACE_COLLECTION_JSON =
            "application/vnd.org.midonet.collection.Interface-v1+json";
    public static final String APPLICATION_HOST_COMMAND_JSON =
            "application/vnd.org.midonet.HostCommand-v1+json";
    public static final String APPLICATION_HOST_COMMAND_COLLECTION_JSON =
            "application/vnd.org.midonet.collection.HostCommand-v1+json";
    public static final String APPLICATION_PORT_V2_JSON =
        "application/vnd.org.midonet.Port-v2+json";
    public static final String APPLICATION_PORT_V2_COLLECTION_JSON
        = "application/vnd.org.midonet.collection.Port-v2+json";
    public static final String APPLICATION_PORT_LINK_JSON =
            "application/vnd.org.midonet.PortLink-v1+json";
    public static final String APPLICATION_ROUTE_JSON =
            "application/vnd.org.midonet.Route-v1+json";
    public static final String APPLICATION_ROUTE_COLLECTION_JSON =
            "application/vnd.org.midonet.collection.Route-v1+json";
    public static final String APPLICATION_PORTGROUP_JSON =
            "application/vnd.org.midonet.PortGroup-v1+json";
    public static final String APPLICATION_PORTGROUP_COLLECTION_JSON =
            "application/vnd.org.midonet.collection.PortGroup-v1+json";
    public static final String APPLICATION_PORTGROUP_PORT_JSON =
            "application/vnd.org.midonet.PortGroupPort-v1+json";
    public static final String APPLICATION_PORTGROUP_PORT_COLLECTION_JSON =
            "application/vnd.org.midonet.collection.PortGroupPort-v1+json";
    public static final String APPLICATION_CHAIN_JSON =
            "application/vnd.org.midonet.Chain-v1+json";
    public static final String APPLICATION_CHAIN_COLLECTION_JSON =
            "application/vnd.org.midonet.collection.Chain-v1+json";
    public static final String APPLICATION_RULE_JSON =
            "application/vnd.org.midonet.Rule-v1+json";
    public static final String APPLICATION_RULE_COLLECTION_JSON =
            "application/vnd.org.midonet.collection.Rule-v1+json";
    public static final String APPLICATION_BGP_JSON =
            "application/vnd.org.midonet.Bgp-v1+json";
    public static final String APPLICATION_BGP_COLLECTION_JSON =
            "application/vnd.org.midonet.collection.Bgp-v1+json";
    public static final String APPLICATION_AD_ROUTE_JSON =
            "application/vnd.org.midonet.AdRoute-v1+json";
    public static final String APPLICATION_AD_ROUTE_COLLECTION_JSON =
            "application/vnd.org.midonet.collection.AdRoute-v1+json";

    /* DHCP configuration types. */
    public static final String APPLICATION_DHCP_SUBNET_JSON =
            "application/vnd.org.midonet.DhcpSubnet-v1+json";
    public static final String APPLICATION_DHCP_SUBNET_COLLECTION_JSON =
            "application/vnd.org.midonet.collection.DhcpSubnet-v1+json";
    public static final String APPLICATION_DHCP_HOST_JSON =
            "application/vnd.org.midonet.DhcpHost-v1+json";
    public static final String APPLICATION_DHCP_HOST_COLLECTION_JSON =
            "application/vnd.org.midonet.collection.DhcpHost-v1+json";
    public static final String APPLICATION_DHCPV6_SUBNET_JSON =
            "application/vnd.org.midonet.DhcpV6Subnet-v1+json";
    public static final String APPLICATION_DHCPV6_SUBNET_COLLECTION_JSON =
            "application/vnd.org.midonet.collection.DhcpV6Subnet-v1+json";
    public static final String APPLICATION_DHCPV6_HOST_JSON =
            "application/vnd.org.midonet.DhcpV6Host-v1+json";
    public static final String APPLICATION_DHCPV6_HOST_COLLECTION_JSON =
            "application/vnd.org.midonet.collection.DhcpV6Host-v1+json";

    public static final String
            APPLICATION_MONITORING_QUERY_RESPONSE_COLLECTION_JSON =
            "application/vnd.org.midonet.collection.mgmt." +
                    "MetricQueryResponse-v1+json";
    public static final String APPLICATION_MONITORING_QUERY_COLLECTION_JSON =
            "application/vnd.org.midonet.collection.MetricQuery-v1+json";
    public static final String APPLICATION_METRICS_COLLECTION_JSON =
            "application/vnd.org.midonet.collection.Metric-v1+json";
    public static final String APPLICATION_METRIC_TARGET_JSON =
            "application/vnd.org.midonet.MetricTarget-v1+json";

    // Tunnel Zones
    public static final String APPLICATION_TUNNEL_ZONE_JSON =
            "application/vnd.org.midonet.TunnelZone-v1+json";
    public static final String APPLICATION_TUNNEL_ZONE_COLLECTION_JSON =
            "application/vnd.org.midonet.collection" +
                    ".TunnelZone-v1+json";

    public static final String APPLICATION_TUNNEL_ZONE_HOST_JSON =
            "application/vnd.org.midonet" +
                    ".TunnelZoneHost-v1+json";
    public static final String
            APPLICATION_TUNNEL_ZONE_HOST_COLLECTION_JSON =
            "application/vnd.org.midonet.collection" +
                    ".TunnelZoneHost-v1+json";
    public static final String APPLICATION_CAPWAP_TUNNEL_ZONE_HOST_JSON =
            "application/vnd.org.midonet" +
                    ".CapwapTunnelZoneHost-v1+json";
    public static final String
            APPLICATION_CAPWAP_TUNNEL_ZONE_HOST_COLLECTION_JSON =
            "application/vnd.org.midonet.collection" +
                    ".CapwapTunnelZoneHost-v1+json";
    public static final String APPLICATION_GRE_TUNNEL_ZONE_HOST_JSON =
            "application/vnd.org.midonet" +
                    ".GreTunnelZoneHost-v1+json";
    public static final String
            APPLICATION_GRE_TUNNEL_ZONE_HOST_COLLECTION_JSON =
            "application/vnd.org.midonet.collection" +
                    ".GreTunnelZoneHost-v1+json";
    public static final String APPLICATION_IPSEC_TUNNEL_ZONE_HOST_JSON =
            "application/vnd.org.midonet" +
                    ".IpsecTunnelZoneHost-v1+json";
    public static final String
            APPLICATION_IPSEC_TUNNEL_ZONE_HOST_COLLECTION_JSON =
            "application/vnd.org.midonet.collection" +
                    ".IpsecTunnelZoneHost-v1+json";

    // Host interface - port mapping
    public static final String APPLICATION_HOST_INTERFACE_PORT_JSON =
            "application/vnd.org.midonet" +
                    ".HostInterfacePort-v1+json";
    public static final String
            APPLICATION_HOST_INTERFACE_PORT_COLLECTION_JSON =
            "application/vnd.org.midonet.collection" +
                    ".HostInterfacePort-v1+json";

    // Upgrade Control
    public static final String APPLICATION_WRITE_VERSION_JSON =
            "application/vnd.org.midonet.WriteVersion-v1+json";
    public static final String APPLICATION_SYSTEM_STATE_JSON =
            "application/vnd.org.midonet.SystemState-v1+json";
    public static final String APPLICATION_HOST_VERSION_JSON =
            "application/vnd.org.midonet.HostVersion-v1+json";

    public static final String APPLICATION_IP_ADDR_GROUP_JSON =
            "application/vnd.org.midonet.IpAddrGroup-v1+json";
    public static final String APPLICATION_IP_ADDR_GROUP_COLLECTION_JSON =
            "application/vnd.org.midonet.collection.IpAddrGroup-v1+json";

    public static final String APPLICATION_IP_ADDR_GROUP_ADDR_JSON =
            "application/vnd.org.midonet.IpAddrGroupAddr-v1+json";
    public static final String APPLICATION_IP_ADDR_GROUP_ADDR_COLLECTION_JSON =
            "application/vnd.org.midonet.collection.IpAddrGroupAddr-v1+json";

    // L4LB
    public static final String APPLICATION_HEALTH_MONITOR_JSON =
            "application/vnd.org.midonet.HealthMonitor-v1+json";
    public static final String APPLICATION_LOAD_BALANCER_JSON =
            "application/vnd.org.midonet.LoadBalancer-v1+json";
    public static final String APPLICATION_POOL_MEMBER_JSON =
            "application/vnd.org.midonet.PoolMember-v1+json";
    public static final String APPLICATION_POOL_JSON =
            "application/vnd.org.midonet.Pool-v1+json";

}
