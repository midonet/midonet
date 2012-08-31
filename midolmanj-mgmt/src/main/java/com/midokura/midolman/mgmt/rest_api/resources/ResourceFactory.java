/*
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.mgmt.rest_api.resources;


import com.midokura.midolman.mgmt.rest_api.resources.*;
import com.midokura.packets.IntIPv4;

import javax.ws.rs.PathParam;
import java.util.UUID;

/**
 * Resource factory used by Guice to inject resource classes.
 */
public interface ResourceFactory {

    TenantResource getTenantResource();

    RouterResource getRouterResource();

    BridgeResource getBridgeResource();

    PortResource getPortResource();

    RouteResource getRouteResource();

    ChainResource getChainResource();

    PortGroupResource getPortGroupResource();

    RuleResource getRuleResource();

    BgpResource getBgpResource();

    AdRouteResource getAdRouteResource();

    VpnResource getVpnResource();

    HostResource getHostResource();

    MonitoringResource getMonitoringQueryResource();

    BridgeResource.TenantBridgeResource getTenantBridgeResource(String id);

    ChainResource.TenantChainResource getTenantChainResource(String id);

    PortGroupResource.TenantPortGroupResource getTenantPortGroupResource(
            String id);

    RouterResource.TenantRouterResource getTenantRouterResource(String id);

    AdRouteResource.BgpAdRouteResource getBgpAdRouteResource(UUID id);

    DhcpHostsResource getDhcpAssignmentsResource(UUID bridgeId, IntIPv4 addr);

    PortResource.BridgePortResource getBridgePortResource(UUID id);

    BridgeFilterDbResource getBridgeFilterDbResource(UUID id);

    BridgeDhcpResource getBridgeDhcpResource(UUID id);

    PortResource.BridgePeerPortResource getBridgePeerPortResource(UUID id);

    RuleResource.ChainRuleResource getChainRuleResource(UUID id);

    InterfaceResource getInterfaceResource(UUID id);

    HostCommandResource getHostCommandsResource(UUID id);

    BgpResource.PortBgpResource getPortBgpResource(UUID id);

    VpnResource.PortVpnResource getPortVpnResource(UUID id);

    PortResource.RouterPortResource getRouterPortResource(UUID id);

    RouteResource.RouterRouteResource getRouterRouteResource(UUID id);

    PortResource.RouterPeerPortResource getRouterPeerPortResource(UUID id);

}
