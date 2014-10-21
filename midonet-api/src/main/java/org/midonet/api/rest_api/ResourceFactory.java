/*
 * Copyright 2012-2013 Midokura PTE LTD.
 */
package org.midonet.api.rest_api;

import java.util.UUID;

import org.midonet.api.auth.rest_api.TenantResource;
import org.midonet.api.bgp.rest_api.AdRouteResource;
import org.midonet.api.bgp.rest_api.BgpResource;
import org.midonet.api.dhcp.rest_api.BridgeDhcpResource;
import org.midonet.api.dhcp.rest_api.BridgeDhcpV6Resource;
import org.midonet.api.dhcp.rest_api.DhcpHostsResource;
import org.midonet.api.dhcp.rest_api.DhcpV6HostsResource;
import org.midonet.api.filter.rest_api.ChainResource;
import org.midonet.api.filter.rest_api.IpAddrGroupResource;
import org.midonet.api.filter.rest_api.RuleResource;
import org.midonet.api.host.rest_api.HostInterfacePortResource;
import org.midonet.api.host.rest_api.HostResource;
import org.midonet.api.host.rest_api.InterfaceResource;
import org.midonet.api.host.rest_api.TunnelZoneHostResource;
import org.midonet.api.host.rest_api.TunnelZoneResource;
import org.midonet.api.l4lb.rest_api.HealthMonitorResource;
import org.midonet.api.l4lb.rest_api.LoadBalancerResource;
import org.midonet.api.l4lb.rest_api.PoolMemberResource;
import org.midonet.api.l4lb.rest_api.PoolResource;
import org.midonet.api.l4lb.rest_api.VipResource;
import org.midonet.api.network.rest_api.BridgeResource;
import org.midonet.api.network.rest_api.PortGroupResource;
import org.midonet.api.network.rest_api.PortResource;
import org.midonet.api.network.rest_api.RouteResource;
import org.midonet.api.network.rest_api.RouterResource;
import org.midonet.api.network.rest_api.VtepBindingResource;
import org.midonet.api.network.rest_api.VtepResource;
import org.midonet.api.network.rest_api.VxLanPortBindingResource;
import org.midonet.api.system_data.rest_api.HostVersionResource;
import org.midonet.api.system_data.rest_api.SystemStateResource;
import org.midonet.api.system_data.rest_api.WriteVersionResource;
import org.midonet.packets.IPv4Subnet;
import org.midonet.packets.IPv6Subnet;

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

    IpAddrGroupResource getIpAddrGroupResource();

    IpAddrGroupResource.IpAddrGroupAddrResource getIpAddrGroupAddrResource(
            UUID id);

    IpAddrGroupResource.IpAddrGroupAddrVersionResource
        getIpAddrGroupAddrVersionResource(UUID id, int version);

    RuleResource getRuleResource();

    BgpResource getBgpResource();

    AdRouteResource getAdRouteResource();

    HostResource getHostResource();

    TunnelZoneResource getTunnelZoneResource();

    TunnelZoneHostResource getTunnelZoneHostResource(UUID id);

    HostInterfacePortResource getHostInterfacePortResource(UUID id);

    AdRouteResource.BgpAdRouteResource getBgpAdRouteResource(UUID id);

    DhcpHostsResource getDhcpAssignmentsResource(UUID bridgeId, IPv4Subnet addr);

    DhcpV6HostsResource getDhcpV6AssignmentsResource(UUID bridgeId, IPv6Subnet addr);

    PortResource.BridgePortResource getBridgePortResource(UUID id);

    BridgeDhcpResource getBridgeDhcpResource(UUID id);

    BridgeDhcpV6Resource getBridgeDhcpV6Resource(UUID id);

    PortResource.BridgePeerPortResource getBridgePeerPortResource(UUID id);

    RuleResource.ChainRuleResource getChainRuleResource(UUID id);

    InterfaceResource getInterfaceResource(UUID id);

    BgpResource.PortBgpResource getPortBgpResource(UUID id);

    PortResource.RouterPortResource getRouterPortResource(UUID id);

    RouteResource.RouterRouteResource getRouterRouteResource(UUID id);

    PortResource.RouterPeerPortResource getRouterPeerPortResource(UUID id);

    PortResource.PortGroupPortResource getPortGroupPortResource(UUID id);

    PortGroupResource.PortPortGroupResource getPortPortGroupResource(
            UUID portId);

    WriteVersionResource getWriteVersionResource();

    SystemStateResource getSystemStateResource();

    HostVersionResource getHostVersionResource();

    HealthMonitorResource getHealthMonitorResource();

    LoadBalancerResource getLoadBalancerResource();

    PoolResource.LoadBalancerPoolResource getLoadBalancerPoolResource(UUID id);

    PoolMemberResource getPoolMemberResource();

    PoolMemberResource.PoolPoolMemberResource
        getPoolPoolMemberResource(UUID id);

    PoolResource getPoolResource();

    VipResource getVipResource();

    VipResource.PoolVipResource getPoolVipResource(UUID id);

    VtepResource getVtepResource();

    VtepBindingResource getVtepBindingResource(String ipAddrStr);

    VxLanPortBindingResource getVxLanPortBindingResource(UUID vxLanPortId);
}
