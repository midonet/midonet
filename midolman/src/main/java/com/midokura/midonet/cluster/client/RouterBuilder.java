/*
 * Copyright 2012 Midokura Europe SARL
 */
package com.midokura.midonet.cluster.client;

import com.midokura.midolman.layer3.Route;

public interface RouterBuilder extends ForwardingElementBuilder {
    void setArpCache(ArpCache table);
    void addRoute(Route rt);
    void removeRoute(Route rt);
}
