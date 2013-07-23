/*
 * Copyright 2012-2013 Midokura PTE LTD.
 */
package org.midonet.api.rest_api;

import org.midonet.api.auth.rest_api.TenantResource;
import org.midonet.api.filter.rest_api.RuleResource;
import org.midonet.api.host.rest_api.*;
import org.midonet.api.network.rest_api.*;
import org.midonet.api.bgp.rest_api.AdRouteResource;
import org.midonet.api.bgp.rest_api.BgpResource;
import org.midonet.api.dhcp.rest_api.BridgeDhcpResource;
import org.midonet.api.dhcp.rest_api.BridgeDhcpV6Resource;
import org.midonet.api.dhcp.rest_api.DhcpHostsResource;
import org.midonet.api.dhcp.rest_api.DhcpV6HostsResource;
import org.midonet.api.filter.rest_api.ChainResource;
import org.midonet.api.monitoring.rest_api.MonitoringResource;
import org.midonet.api.tracing.rest_api.TraceConditionResource;
import org.midonet.api.tracing.rest_api.TraceResource;
import org.midonet.packets.IntIPv4;
import org.midonet.packets.IPv6Subnet;

import java.util.UUID;

/**
 * Resource factory used by Guice to inject resource classes.
 */
public interface ResourceFactory {

    TenantResource getTenantResource();

    RouterResource getRouterResource();

    BridgeResource getBridgeResource();

    VlanBridgeResource getVlanBridgeResource();

    PortResource getPortResource();

    RouteResource getRouteResource();

    ChainResource getChainResource();

    PortGroupResource getPortGroupResource();

    RuleResource getRuleResource();

    BgpResource getBgpResource();

    AdRouteResource getAdRouteResource();

    HostResource getHostResource();

    TunnelZoneResource getTunnelZoneResource();

    TunnelZoneHostResource getTunnelZoneHostResource(UUID id);

    HostInterfacePortResource getHostInterfacePortResource(UUID id);

    MonitoringResource getMonitoringQueryResource();

    AdRouteResource.BgpAdRouteResource getBgpAdRouteResource(UUID id);

    DhcpHostsResource getDhcpAssignmentsResource(UUID bridgeId, IntIPv4 addr);

    DhcpV6HostsResource getDhcpV6AssignmentsResource(UUID bridgeId, IPv6Subnet addr);

    PortResource.BridgePortResource getBridgePortResource(UUID id);

    BridgeDhcpResource getBridgeDhcpResource(UUID id);

    BridgeDhcpV6Resource getBridgeDhcpV6Resource(UUID id);

    PortResource.BridgePeerPortResource getBridgePeerPortResource(UUID id);

    RuleResource.ChainRuleResource getChainRuleResource(UUID id);

    InterfaceResource getInterfaceResource(UUID id);

    HostCommandResource getHostCommandsResource(UUID id);

    BgpResource.PortBgpResource getPortBgpResource(UUID id);

    PortResource.RouterPortResource getRouterPortResource(UUID id);

    RouteResource.RouterRouteResource getRouterRouteResource(UUID id);

    PortResource.RouterPeerPortResource getRouterPeerPortResource(UUID id);

    PortResource.PortGroupPortResource getPortGroupPortResource(UUID id);

    PortResource.VlanBridgeInteriorPortResource getVlanBridgeInteriorPortResource(UUID id);

    PortResource.VlanBridgeTrunkPortResource getVlanBridgeTrunkPortResource(UUID id);

    TraceConditionResource getTraceConditionResource();

    TraceResource getTraceResource();
}
