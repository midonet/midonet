package com.midokura.midolman.rules;

import java.util.Set;
import java.util.UUID;

import com.midokura.midolman.layer4.NwTpPair;

public class DnatRule extends ForwardNatRule {

    private static final long serialVersionUID = 5094276887032210331L;

    public DnatRule(Condition condition, Set<NatTarget> targets, Action action) {
        super(condition, targets, action);
    }

    @Override
    public void apply(UUID inPortId, UUID outPortId, RuleResult res) {
        if (null == natMap)
            return;
        NwTpPair conn = natMap.lookupDnatFwd(res.match.getNetworkSource(),
                res.match.getTransportSource(),
                res.match.getNetworkDestination(),
                res.match.getTransportDestination());
        if (null == conn)
            conn = natMap.allocateDnat(res.match.getNetworkSource(),
                    res.match.getTransportSource(),
                    res.match.getNetworkDestination(),
                    res.match.getTransportDestination(), targets);
        // TODO(pino): deal with case that conn couldn't be allocated.
        res.match.setNetworkDestination(conn.nwAddr);
        res.match.setTransportDestination(conn.tpPort);
        res.action = action;
        res.trackConnection = true;
    }

    @Override
    public int hashCode() {
        return 11 * super.hashCode() + "DnatRule".hashCode();
    }

    @Override
    public boolean equals(Object other) {
        if (this == other)
            return true;
        if (!(other instanceof DnatRule))
            return false;
        return super.equals(other);
    }
}
