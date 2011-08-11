package com.midokura.midolman.rules;

import java.util.Set;
import java.util.UUID;

import com.midokura.midolman.layer4.NwTpPair;

public class SnatRule extends ForwardNatRule {

    private static final long serialVersionUID = 2848105655045425499L;

    public SnatRule(Condition condition, Set<NatTarget> targets,
            Action action) {
        super(condition, targets, action);
    }

    @Override
    public void apply(UUID inPortId, UUID outPortId, RuleResult res) {
        if (null == natMap)
            return;
        NwTpPair conn = natMap.lookupSnatFwd(
                res.match.getNetworkSource(),
                res.match.getTransportSource(),
                res.match.getNetworkDestination(),
                res.match.getTransportDestination());
        if (null == conn)
            conn = natMap.allocateSnat(
                    res.match.getNetworkSource(),
                    res.match.getTransportSource(),
                    res.match.getNetworkDestination(),
                    res.match.getTransportDestination(),
                    targets);
        // TODO(pino): deal with case that conn couldn't be allocated.
        res.match.setNetworkSource(conn.nwAddr);
        res.match.setTransportSource(conn.tpPort);
        res.action = action;
        res.trackConnection = true;
    }

    @Override
    public int hashCode() {
        return 11 * super.hashCode() + "SnatRule".hashCode();
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof SnatRule)) return false;
        return super.equals(other);
    }
}
