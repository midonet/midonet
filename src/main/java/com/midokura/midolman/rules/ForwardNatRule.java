package com.midokura.midolman.rules;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import com.midokura.midolman.layer4.NwTpPair;

public class ForwardNatRule extends NatRule {

    private static final long serialVersionUID = -6779063463988814100L;
    protected transient Set<NatTarget> targets;

    public ForwardNatRule(Condition condition, Set<NatTarget> targets,
            Action action, boolean dnat) {
        super(condition, action, dnat);
        this.targets = targets;
        if (null == targets || targets.size() == 0)
            throw new IllegalArgumentException(
                    "A forward nat rule must have targets.");
    }

    @Override
    public void apply(UUID inPortId, UUID outPortId, RuleResult res) {
        if (null == natMap)
            return;
        if (dnat)
            applyDnat(inPortId, outPortId, res);
        else
            applySnat(inPortId, outPortId, res);
    }

    public void applyDnat(UUID inPortId, UUID outPortId, RuleResult res) {
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

    public void applySnat(UUID inPortId, UUID outPortId, RuleResult res) {
        NwTpPair conn = natMap.lookupSnatFwd(res.match.getNetworkSource(),
                res.match.getTransportSource(),
                res.match.getNetworkDestination(),
                res.match.getTransportDestination());
        if (null == conn)
            conn = natMap.allocateSnat(res.match.getNetworkSource(),
                    res.match.getTransportSource(),
                    res.match.getNetworkDestination(),
                    res.match.getTransportDestination(), targets);
        // TODO(pino): deal with case that conn couldn't be allocated.
        res.match.setNetworkSource(conn.nwAddr);
        res.match.setTransportSource(conn.tpPort);
        res.action = action;
        res.trackConnection = true;
    }

    // Used by RuleEngine to discover resources that must be initialized
    // or preserved. Not all NatRules have NatTargets (e.g. reverse nats).
    public Set<NatTarget> getNatTargets() {
        return targets;
    }

    @Override
    public int hashCode() {
        int hash = super.hashCode();
        return 29 * hash + targets.hashCode();
    }

    @Override
    public boolean equals(Object other) {
        if (this == other)
            return true;
        if (!(other instanceof ForwardNatRule))
            return false;
        if (!super.equals(other))
            return false;
        ForwardNatRule r = (ForwardNatRule) other;
        return targets.equals(r.targets);
    }

    private void writeObject(ObjectOutputStream out) throws IOException {
        out.defaultWriteObject();
        out.writeInt(targets.size());
        for (NatTarget nat : targets)
            out.writeObject(nat);
    }

    private void readObject(ObjectInputStream in) throws IOException,
            ClassNotFoundException {
        in.defaultReadObject();
        targets = new HashSet<NatTarget>();
        int numTargets = in.readInt();
        for (int i = 0; i < numTargets; i++)
            targets.add((NatTarget) in.readObject());
    }
}
