/*
* Copyright (c) 2014 Midokura SARL, All Rights Reserved.
*/
package org.midonet.api.neutron.loadbalancer;

import java.net.URI;
import java.util.UUID;

import javax.ws.rs.core.UriBuilder;

import org.midonet.api.ResourceUriBuilder;
import org.midonet.api.neutron.NeutronUriBuilder;

public class LBUriBuilder {

    // LBaaS resources
    public final static String LB = "/lb";
    public final static String VIPS = "/vips";
    public final static String POOLS = "/pools";
    public final static String MEMBERS = "/members";
    public final static String HEALTH_MONITORS = "/health_monitors";

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

    // Pools
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
}
