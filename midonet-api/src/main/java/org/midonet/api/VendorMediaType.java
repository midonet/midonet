/*
 * Copyright 2011 Midokura KK
 * Copyright 2012 Midokura PTE LTD.
 * Copyright 2013 Midokura PTE LTD.
 */
package org.midonet.api;

/**
 * Vendor media types that represent resources available in MidoNet API.
 */
public class VendorMediaType {

    public static final String APPLICATION_JSON =
            "application/vnd.org.midonet.Application-v1+json";
    public static final String APPLICATION_ERROR_JSON =
            "application/vnd.org.midonet.Error-v1+json";
    public static final String APPLICATION_TENANT_JSON =
            "application/vnd.org.midonet.Tenant-v1+json";
    public static final String APPLICATION_TENANT_COLLECTION_JSON =
            "application/vnd.org.midonet.collection.Tenant-v1+json";
    public static final String APPLICATION_ROUTER_JSON =
            "application/vnd.org.midonet.Router-v1+json";
    public static final String APPLICATION_ROUTER_COLLECTION_JSON =
            "application/vnd.org.midonet.collection.Router-v1+json";
    public static final String APPLICATION_BRIDGE_JSON =
            "application/vnd.org.midonet.Bridge-v1+json";
    public static final String APPLICATION_VLAN_BRIDGE_JSON =
            "application/vnd.org.midonet.VlanBridge-v1+json";
    public static final String APPLICATION_VLAN_BRIDGE_COLLECTION_JSON =
            "application/vnd.org.midonet.collection.VlanBridge-v1+json";
    public static final String APPLICATION_BRIDGE_COLLECTION_JSON =
            "application/vnd.org.midonet.collection.Bridge-v1+json";
    public static final String APPLICATION_MAC_PORT_JSON =
            "application/vnd.com.midokura.midolman.mgmt.MacPort-v1+json";
    public static final String APPLICATION_MAC_PORT_COLLECTION_JSON =
            "application/vnd.com.midokura.midolman.mgmt.collection." +
                    "MacPort-v1+json";
    public static final String APPLICATION_IP4_MAC_JSON =
            "application/vnd.com.midokura.midolman.mgmt.IP4Mac-v1+json";
    public static final String APPLICATION_IP4_MAC_COLLECTION_JSON =
            "application/vnd.com.midokura.midolman.mgmt.collection." +
                    "IP4Mac-v1+json";
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
    public static final String APPLICATION_PORT_JSON =
            "application/vnd.org.midonet.Port-v1+json";
    public static final String APPLICATION_PORT_COLLECTION_JSON =
            "application/vnd.org.midonet.collection.Port-v1+json";
    public static final String APPLICATION_BRIDGEPORT_JSON =
            "application/vnd.org.midonet.BridgePort-v1+json";
    public static final String APPLICATION_ROUTERPORT_JSON =
            "application/vnd.org.midonet.RouterPort-v1+json";
    public static final String APPLICATION_BRIDGEPORT_COLLECTION_JSON =
            "application/vnd.org.midonet.collection.BridgePort-v1+json";
    public static final String APPLICATION_ROUTERPORT_COLLECTION_JSON =
            "application/vnd.org.midonet.collection.RouterPort-v1+json";
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

    // Trace Conditions
    public static final String APPLICATION_CONDITION_JSON =
            "application/vnd.org.midonet.Condition-v1+json";
    public static final String APPLICATION_CONDITION_COLLECTION_JSON =
            "application/vnd.org.midonet.collection" +
                    ".Condition-v1+json";

    // Packet Trace
    public static final String APPLICATION_TRACE_JSON =
            "application/vnd.org.midonet.Trace-v1+json";
    public static final String APPLICATION_TRACE_COLLECTION_JSON =
            "application/vnd.org.midonet.collection" +
                    ".Trace-v1+json";
}
