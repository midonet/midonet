package com.midokura.midolman.rules;

import java.util.UUID;

import com.midokura.midolman.layer4.NwTpPair;

public class ReverseNatRule extends NatRule {

    private static final long serialVersionUID = 7487526421247959225L;

    public ReverseNatRule(Condition condition, Action action, boolean dnat) {
        super(condition, action, dnat);
    }

    @Override
    public void apply(UUID inPortId, UUID outPortId, RuleResult res) {
        if (dnat)
            applyReverseDnat(inPortId, outPortId, res);
        else
            applyReverseSnat(inPortId, outPortId, res);
    }

    private void applyReverseDnat(UUID inPortId, UUID outPortId, RuleResult res) {
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

    private void applyReverseSnat(UUID inPortId, UUID outPortId, RuleResult res) {
        if (null == natMap)
            return;
        NwTpPair origConn = natMap.lookupSnatRev(
                res.match.getNetworkDestination(),
                res.match.getTransportDestination(),
                res.match.getNetworkSource(), res.match.getTransportSource());
        if (null == origConn)
            return;
        res.match.setNetworkDestination(origConn.nwAddr);
        res.match.setTransportDestination(origConn.tpPort);
        res.action = action;
    }

    @Override
    public int hashCode() {
        return super.hashCode() * 29 + "ReverseNatRule".hashCode();
    }

    @Override
    public boolean equals(Object other) {
        if (this == other)
            return true;
        if (!(other instanceof ReverseNatRule))
            return false;
        return super.equals(other);
    }
}
