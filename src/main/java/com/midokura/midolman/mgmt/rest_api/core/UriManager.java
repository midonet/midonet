/*
 * @(#)ResourcePath.java        1.6 11/11/15
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.mgmt.rest_api.core;

import java.net.URI;

import javax.ws.rs.core.UriBuilder;

import com.midokura.midolman.mgmt.data.dto.Bgp;
import com.midokura.midolman.mgmt.data.dto.Bridge;
import com.midokura.midolman.mgmt.data.dto.Chain;
import com.midokura.midolman.mgmt.data.dto.Port;
import com.midokura.midolman.mgmt.data.dto.Route;
import com.midokura.midolman.mgmt.data.dto.Router;
import com.midokura.midolman.mgmt.data.dto.Tenant;
import com.midokura.midolman.mgmt.data.dto.Vif;
import com.midokura.midolman.mgmt.data.dto.Vpn;

public class UriManager {

    public static final String ROOT = "/";
    public static final String ADMIN = "/admin";
    public static final String INIT = "/init";
    public static final String TENANTS = "/tenants";
    public static final String ROUTERS = "/routers";
    public static final String BRIDGES = "/bridges";
    public static final String PORTS = "/ports";
    public static final String CHAINS = "/chains";
    public static final String TABLES = "/tables";
    public static final String RULES = "/rules";
    public static final String ROUTES = "/routes";
    public static final String VIFS = "/vifs";
    public static final String BGP = "/bgps";
    public static final String AD_ROUTES = "/ad_routes";
    public static final String VPN = "/vpns";

    public static URI getRoot(URI baseUri) {
        return UriBuilder.fromUri(baseUri).path(ROOT).build();
    }

    public static URI getAdmin(URI baseUri) {
        return UriBuilder.fromUri(getRoot(baseUri)).path(ADMIN).build();
    }

    public static URI getInit(URI baseUri) {
        return UriBuilder.fromUri(getAdmin(baseUri)).path(INIT).build();
    }

    public static URI getTenants(URI baseUri) {
        return UriBuilder.fromUri(getRoot(baseUri)).path(TENANTS).build();
    }

    public static URI getTenant(URI baseUri, Tenant tenant) {
        return UriBuilder.fromUri(getTenants(baseUri)).path(tenant.getId())
                .build();
    }

    public static URI getRouters(URI baseUri) {
        return UriBuilder.fromUri(getRoot(baseUri)).path(ROUTERS).build();
    }

    public static URI getRouter(URI baseUri, Router router) {
        return UriBuilder.fromUri(getRouters(baseUri))
                .path(router.getId().toString()).build();
    }

    public static URI getTenantRouters(URI baseUri, Tenant tenant) {
        return UriBuilder.fromUri(getTenant(baseUri, tenant)).path(ROUTERS)
                .build();
    }

    public static URI getBridges(URI baseUri) {
        return UriBuilder.fromUri(getRoot(baseUri)).path(BRIDGES).build();
    }

    public static URI getBridge(URI baseUri, Bridge bridge) {
        return UriBuilder.fromUri(getBridges(baseUri))
                .path(bridge.getId().toString()).build();
    }

    public static URI getTenantBridges(URI baseUri, Tenant tenant) {
        return UriBuilder.fromUri(getTenant(baseUri, tenant)).path(BRIDGES)
                .build();
    }

    public static URI getPorts(URI baseUri) {
        return UriBuilder.fromUri(getRoot(baseUri)).path(PORTS).build();
    }

    public static URI getPort(URI baseUri, Port port) {
        return UriBuilder.fromUri(getPorts(baseUri))
                .path(port.getId().toString()).build();
    }

    public static URI getBridgePorts(URI baseUri, Bridge bridge) {
        return UriBuilder.fromUri(getBridge(baseUri, bridge)).path(PORTS)
                .build();
    }

    public static URI getRouterPorts(URI baseUri, Router router) {
        return UriBuilder.fromUri(getRouter(baseUri, router)).path(PORTS)
                .build();
    }

    public static URI getChains(URI baseUri) {
        return UriBuilder.fromUri(getRoot(baseUri)).path(CHAINS).build();
    }

    public static URI getChain(URI baseUri, Chain chain) {
        return UriBuilder.fromUri(getChains(baseUri))
                .path(chain.getId().toString()).build();
    }

    public static URI getRouterChains(URI baseUri, Router router) {
        return UriBuilder.fromUri(getRouter(baseUri, router)).path(CHAINS)
                .build();
    }

    public static URI getRoutes(URI baseUri) {
        return UriBuilder.fromUri(getRoot(baseUri)).path(ROUTES).build();
    }

    public static URI getRoute(URI baseUri, Route route) {
        return UriBuilder.fromUri(getRoutes(baseUri))
                .path(route.getId().toString()).build();
    }

    public static URI getRouterRoutes(URI baseUri, Router router) {
        return UriBuilder.fromUri(getRouter(baseUri, router)).path(ROUTES)
                .build();
    }

    public static URI getRouterRouters(URI baseUri, Router router) {
        return UriBuilder.fromUri(getRouter(baseUri, router)).path(ROUTERS)
                .build();
    }

    public static URI getBgps(URI baseUri) {
        return UriBuilder.fromUri(getRoot(baseUri)).path(BGP).build();
    }

    public static URI getBgp(URI baseUri, Bgp bgp) {
        return UriBuilder.fromUri(getBgps(baseUri))
                .path(bgp.getId().toString()).build();
    }

    public static URI getPortBgps(URI baseUri, Port port) {
        return UriBuilder.fromUri(getPort(baseUri, port)).path(BGP).build();
    }

    public static URI getVpns(URI baseUri) {
        return UriBuilder.fromUri(getRoot(baseUri)).path(VPN).build();
    }

    public static URI getVpn(URI baseUri, Vpn vpn) {
        return UriBuilder.fromUri(getVpns(baseUri))
                .path(vpn.getId().toString()).build();
    }

    public static URI getPortVpns(URI baseUri, Port port) {
        return UriBuilder.fromUri(getPort(baseUri, port)).path(VPN).build();
    }

    public static URI getVifs(URI baseUri) {
        return UriBuilder.fromUri(getRoot(baseUri)).path(VIFS).build();
    }

    public static URI getVif(URI baseUri, Vif vif) {
        return UriBuilder.fromUri(getVifs(baseUri))
                .path(vif.getId().toString()).build();
    }
}
