package com.midokura.midolman.rules;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.HashSet;
import java.util.Set;

public abstract class ForwardNatRule extends NatRule {

    private static final long serialVersionUID = -6779063463988814100L;
    protected transient Set<NatTarget> targets;

    public ForwardNatRule(Condition condition, Set<NatTarget> targets,
            Action action) {
        super(condition, action);
        this.targets = targets;
        if (null == targets || targets.size() == 0)
            throw new IllegalArgumentException(
                    "A forward nat rule must have targets.");
    }

    @Override
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
