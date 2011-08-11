package com.midokura.midolman.rules;

import java.util.Set;
import java.util.UUID;

import com.midokura.midolman.routing.NwTpPair;

public class SnatRule extends NatRule {

    private Set<NatTarget> targets;

    public SnatRule(Condition condition, Set<NatTarget> targets,
            Action action) {
        super(condition, action);
        this.targets = targets;
        if (null == targets || targets.size() == 0)
            throw new IllegalArgumentException("DnatRule must have targets.");
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
    public Set<NatTarget> getNatTargets() {
        return targets;
    }

    @Override
    public int hashCode() {
        int hash = super.hashCode();
        return 31*hash + targets.hashCode();
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof DnatRule)) return false;
        if (!super.equals(other))
            return false;
        SnatRule r = (SnatRule)other;
        for (NatTarget nt: targets) {
            if (!r.targets.contains(nt)) return false;
        }
        for (NatTarget nt: r.targets) {
            if (!targets.contains(nt)) return false;
        }
        return true;
    }
}
