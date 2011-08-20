package com.midokura.midolman.layer3;

import java.util.List;

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
        List<Route> routes = table.lookup(pktMatch.getNetworkSource(),
                pktMatch.getNetworkDestination());
        if (routes.size() > 0)
            return routes.get(0);
        else
            return null;
    }

}
