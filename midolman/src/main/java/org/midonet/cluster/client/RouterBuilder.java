/*
 * Copyright 2012 Midokura Europe SARL
 */
package org.midonet.cluster.client;

import org.midonet.midolman.layer3.Route;

public interface RouterBuilder extends ForwardingElementBuilder {
    void setArpCache(ArpCache table);
    void addRoute(Route rt);
    void removeRoute(Route rt);
}
