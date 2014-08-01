/*
* Copyright (c) 2014 Midokura SARL, All Rights Reserved.
*/
package org.midonet.api.neutron.loadbalancer;

import java.net.URI;
import java.util.UUID;

import javax.ws.rs.core.UriBuilder;

import org.midonet.api.ResourceUriBuilder;
import org.midonet.api.neutron.NeutronUriBuilder;

public final class LBUriBuilder {

    // LBaaS resources
    public static final String LB = "/lb";
    public static final String VIPS = "/vips";
    public static final String POOLS = "/pools";
    public static final String MEMBERS = "/members";
    public static final String HEALTH_MONITORS = "/health_monitors";
    public static final String POOL_HEALTH_MONITOR = "/pool_health_monitor";

    private LBUriBuilder() {
        // not called
    }

    public static URI getLoadBalancer(URI baseUri) {
        return UriBuilder.fromUri(NeutronUriBuilder.getNeutron(baseUri)).path(
            LB).build();
    }

    // Vips
    public static URI getVips(URI baseUri) {
        return UriBuilder.fromUri(getLoadBalancer(baseUri)).path(VIPS).build();
    }

    public static URI getVip(URI baseUri, UUID id) {
        return UriBuilder.fromUri(
            getVips(baseUri)).path(id.toString()).build();
    }

    public static String getVipTemplate(URI baseUri) {
        return ResourceUriBuilder.buildIdTemplateUri(getVips(baseUri));
    }

    // Pools
    public static URI getPools(URI baseUri) {
        return UriBuilder.fromUri(getLoadBalancer(baseUri)).path(POOLS).build();
    }

    public static URI getPool(URI baseUri, UUID id) {
        return UriBuilder.fromUri(
            getPools(baseUri)).path(id.toString()).build();
    }

    public static String getPoolTemplate(URI baseUri) {
        return ResourceUriBuilder.buildIdTemplateUri(getPools(baseUri));
    }

    // Pool Members
    public static URI getMembers(URI baseUri) {
        return UriBuilder.fromUri(getLoadBalancer(baseUri)).path(MEMBERS)
            .build();
    }

    public static URI getMember(URI baseUri, UUID id) {
        return UriBuilder.fromUri(
            getMembers(baseUri)).path(id.toString()).build();
    }

    public static String getMemberTemplate(URI baseUri) {
        return ResourceUriBuilder.buildIdTemplateUri(getMembers(baseUri));
    }

    // Health Monitors
    public static URI getHealthMonitors(URI baseUri) {
        return UriBuilder.fromUri(getLoadBalancer(baseUri))
            .path(HEALTH_MONITORS).build();
    }

    public static URI getHealthMonitor(URI baseUri, UUID id) {
        return UriBuilder.fromUri(
            getHealthMonitors(baseUri)).path(id.toString()).build();
    }

    public static String getHealthMonitorTemplate(URI baseUri) {
        return ResourceUriBuilder.buildIdTemplateUri(
            getHealthMonitors(baseUri));
    }

    // Pool Health Monitor
    public static URI getPoolHealthMonitor(URI baseUri) {
        return UriBuilder.fromUri(getLoadBalancer(baseUri))
            .path(POOL_HEALTH_MONITOR).build();
    }
}
