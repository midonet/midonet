/*
 * Copyright 2011 Midokura KK
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midonet.api;

/**
 * Vendor media types
 */
public class VendorMediaType {

    public static final String APPLICATION_JSON = "application/vnd.org.midonet.Application+json";
    public static final String APPLICATION_ERROR_JSON = "application/vnd.org.midonet.Error+json";
    public static final String APPLICATION_ROUTER_JSON = "application/vnd.org.midonet.Router+json";
    public static final String APPLICATION_ROUTER_COLLECTION_JSON = "application/vnd.org.midonet.collection.Router+json";
    public static final String APPLICATION_BRIDGE_JSON = "application/vnd.org.midonet.Bridge+json";
    public static final String APPLICATION_BRIDGE_COLLECTION_JSON = "application/vnd.org.midonet.collection.Bridge+json";
    public static final String APPLICATION_HOST_JSON = "application/vnd.org.midonet.Host+json";
    public static final String APPLICATION_HOST_COLLECTION_JSON = "application/vnd.org.midonet.collection.Host+json";
    public static final String APPLICATION_INTERFACE_JSON = "application/vnd.org.midonet.Interface+json";
    public static final String APPLICATION_INTERFACE_COLLECTION_JSON = "application/vnd.org.midonet.collection.Interface+json";
    public static final String APPLICATION_HOST_COMMAND_JSON = "application/vnd.org.midonet.HostCommand+json";
    public static final String APPLICATION_HOST_COMMAND_COLLECTION_JSON = "application/vnd.org.midonet.collection.HostCommand+json";
    public static final String APPLICATION_PORT_JSON = "application/vnd.org.midonet.Port+json";
    public static final String APPLICATION_PORT_COLLECTION_JSON = "application/vnd.org.midonet.collection.Port+json";
    public static final String APPLICATION_PORT_LINK_JSON = "application/vnd.org.midonet.PortLink+json";
    public static final String APPLICATION_ROUTE_JSON = "application/vnd.org.midonet.Route+json";
    public static final String APPLICATION_ROUTE_COLLECTION_JSON = "application/vnd.org.midonet.collection.Route+json";
    public static final String APPLICATION_PORTGROUP_JSON = "application/vnd.org.midonet.PortGroup+json";
    public static final String APPLICATION_PORTGROUP_COLLECTION_JSON = "application/vnd.org.midonet.collection.PortGroup+json";
    public static final String APPLICATION_PORTGROUP_PORT_JSON =
            "application/vnd.org.midonet.PortGroupPort+json";
    public static final String APPLICATION_PORTGROUP_PORT_COLLECTION_JSON =
            "application/vnd.org.midonet.collection.PortGroupPort+json";
    public static final String APPLICATION_CHAIN_JSON = "application/vnd.org.midonet.Chain+json";
    public static final String APPLICATION_CHAIN_COLLECTION_JSON = "application/vnd.org.midonet.collection.Chain+json";
    public static final String APPLICATION_RULE_JSON = "application/vnd.org.midonet.Rule+json";
    public static final String APPLICATION_RULE_COLLECTION_JSON = "application/vnd.org.midonet.collection.Rule+json";
    public static final String APPLICATION_BGP_JSON = "application/vnd.org.midonet.Bgp+json";
    public static final String APPLICATION_BGP_COLLECTION_JSON = "application/vnd.org.midonet.collection.Bgp+json";
    public static final String APPLICATION_AD_ROUTE_JSON = "application/vnd.org.midonet.AdRoute+json";
    public static final String APPLICATION_AD_ROUTE_COLLECTION_JSON = "application/vnd.org.midonet.collection.AdRoute+json";
    public static final String APPLICATION_VPN_JSON = "application/vnd.org.midonet.Vpn+json";
    public static final String APPLICATION_VPN_COLLECTION_JSON = "application/vnd.org.midonet.collection.Vpn+json";

    /* DHCP configuration types. */
    public static final String APPLICATION_DHCP_SUBNET_JSON = "application/vnd.org.midonet.DhcpSubnet+json";
    public static final String APPLICATION_DHCP_SUBNET_COLLECTION_JSON = "application/vnd.org.midonet.collection.DhcpSubnet+json";
    public static final String APPLICATION_DHCP_HOST_JSON = "application/vnd.org.midonet.DhcpHost+json";
    public static final String APPLICATION_DHCP_HOST_COLLECTION_JSON = "application/vnd.org.midonet.collection.DhcpHost+json";

    public static final String APPLICATION_MONITORING_QUERY_RESPONSE_COLLECTION_JSON =
            "application/vnd.org.midonet.collection.mgmt.MetricQueryResponse+json";
    public static final String APPLICATION_MONITORING_QUERY_COLLECTION_JSON = "application/vnd.org.midonet.collection.MetricQuery+json";
    public static final String APPLICATION_METRICS_COLLECTION_JSON = "application/vnd.org.midonet.collection.Metric+json";
    public static final String APPLICATION_METRIC_TARGET_JSON = "application/vnd.org.midonet.MetricTarget+json";

    // Tunnel Zones
    public static final String APPLICATION_TUNNEL_ZONE_JSON =
            "application/vnd.org.midonet.TunnelZone+json";
    public static final String APPLICATION_TUNNEL_ZONE_COLLECTION_JSON =
            "application/vnd.org.midonet.collection" +
                    ".TunnelZone+json";

    public static final String APPLICATION_TUNNEL_ZONE_HOST_JSON =
        "application/vnd.org.midonet" +
            ".TunnelZoneHost+json";
    public static final String
        APPLICATION_TUNNEL_ZONE_HOST_COLLECTION_JSON =
        "application/vnd.org.midonet.collection" +
            ".TunnelZoneHost+json";
    public static final String APPLICATION_CAPWAP_TUNNEL_ZONE_HOST_JSON =
            "application/vnd.org.midonet" +
                    ".CapwapTunnelZoneHost+json";
    public static final String
            APPLICATION_CAPWAP_TUNNEL_ZONE_HOST_COLLECTION_JSON =
            "application/vnd.org.midonet.collection" +
                    ".CapwapTunnelZoneHost+json";
    public static final String APPLICATION_GRE_TUNNEL_ZONE_HOST_JSON =
            "application/vnd.org.midonet" +
                    ".GreTunnelZoneHost+json";
    public static final String
            APPLICATION_GRE_TUNNEL_ZONE_HOST_COLLECTION_JSON =
            "application/vnd.org.midonet.collection" +
                    ".GreTunnelZoneHost+json";
    public static final String APPLICATION_IPSEC_TUNNEL_ZONE_HOST_JSON =
            "application/vnd.org.midonet" +
                    ".IpsecTunnelZoneHost+json";
    public static final String
            APPLICATION_IPSEC_TUNNEL_ZONE_HOST_COLLECTION_JSON =
            "application/vnd.org.midonet.collection" +
                    ".IpsecTunnelZoneHost+json";

    // Host interface - port mapping
    public static final String APPLICATION_HOST_INTERFACE_PORT_JSON =
            "application/vnd.org.midonet" +
                    ".HostInterfacePort+json";
    public static final String
            APPLICATION_HOST_INTERFACE_PORT_COLLECTION_JSON =
            "application/vnd.org.midonet.collection" +
                    ".HostInterfacePort+json";
}
