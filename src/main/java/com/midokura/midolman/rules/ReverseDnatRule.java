package com.midokura.midolman.rules;

import java.util.UUID;

import com.midokura.midolman.layer4.NwTpPair;

public class ReverseDnatRule extends NatRule {

    private static final long serialVersionUID = 7487526421247959225L;

    public ReverseDnatRule(Condition condition, Action action) {
        super(condition, action);
    }

    @Override
    public void apply(UUID inPortId, UUID outPortId, RuleResult res) {
        if (null == natMap)
            return;
        NwTpPair origConn = natMap.lookupDnatRev(
                res.match.getNetworkDestination(),
                res.match.getTransportDestination(),
                res.match.getNetworkSource(), res.match.getTransportSource());
        if (null == origConn)
            return;
        res.match.setNetworkSource(origConn.nwAddr);
        res.match.setTransportSource(origConn.tpPort);
        res.action = action;
    }

    @Override
    public int hashCode() {
        return super.hashCode() * 11 + "ReverseDnatRule".hashCode();
    }

    @Override
    public boolean equals(Object other) {
        if (this == other)
            return true;
        if (!(other instanceof ReverseDnatRule))
            return false;
        return super.equals(other);
    }
}
