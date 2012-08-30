/*
 * Copyright 2011 Midokura KK
 * Copyright 2012 Midokura Europe SARL
 */
package com.midokura.midolman.layer3;

import com.midokura.midolman.openflow.MidoMatch;

public class DummyLoadBalancer implements LoadBalancer {
    private ReplicatedRoutingTable table;

    public DummyLoadBalancer(ReplicatedRoutingTable table) {
        this.table = table;
        if (null == table)
            throw new NullPointerException("Cannot use a null routing table.");
    }

    @Override
    public Route lookup(MidoMatch pktMatch) {
        Iterable<Route> routes = table.lookup(pktMatch.getNetworkSource(),
                pktMatch.getNetworkDestination());
        if (routes.iterator().hasNext())
            return routes.iterator().next();
        else
            return null;
    }

}
