package com.midokura.midolman.rules;

import java.util.Set;
import java.util.UUID;

public class SnatRule extends Rule {

    Set<NatTarget> targets;

    public SnatRule(Condition condition, Set<NatTarget> targets) {
        super(condition);
        this.targets = targets;
        if (null == targets || targets.size() == 0)
            throw new IllegalArgumentException("DnatRule must have targets.");
    }

    @Override
    protected void apply(UUID inPortId, UUID outPortId, RuleResult res) {
        // TODO Auto-generated method stub
        
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
