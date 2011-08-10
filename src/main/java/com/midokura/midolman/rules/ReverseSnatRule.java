package com.midokura.midolman.rules;

import java.util.UUID;

import com.midokura.midolman.routing.NwTpPair;

public class ReverseSnatRule extends NatRule {

    public ReverseSnatRule(Condition condition, Action action) {
        super(condition, action);
    }

    @Override
    public void apply(UUID inPortId, UUID outPortId, RuleResult res) {
        NwTpPair origConn = natMap.lookupSnatRev(
                res.match.getNetworkDestination(),
                res.match.getTransportDestination(),
                res.match.getNetworkSource(),
                res.match.getTransportSource());
        if (null == origConn)
            return;
        res.match.setNetworkDestination(origConn.nwAddr);
        res.match.setTransportDestination(origConn.tpPort);
        res.action = action;
        return;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof ReverseDnatRule)) return false;
        return super.equals(other);
    }
}
