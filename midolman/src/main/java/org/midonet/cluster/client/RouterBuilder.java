/*
 * Copyright 2012 Midokura Europe SARL
 */
package org.midonet.cluster.client;

import java.util.UUID;

import org.midonet.midolman.layer3.Route;

public interface RouterBuilder extends DeviceBuilder<RouterBuilder> {
    void setArpCache(ArpCache table);
    void setLoadBalancer(UUID loadBalancerId);
    void addRoute(Route rt);
    void removeRoute(Route rt);
}