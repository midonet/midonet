package com.midokura.midolman.rules;

import java.util.Set;

import com.midokura.midolman.routing.NatMapping;

public abstract class NatRule extends Rule {

    protected NatMapping natMap;

    public NatRule(Condition condition, Action action) {
        super(condition, action);
        if(action != Action.ACCEPT && action != Action.CONTINUE &&
                action != Action.RETURN)
            throw new IllegalArgumentException("A nat rule's action " +
                    "must be one of: ACCEPT, CONTINUE, or RETURN.");
    }

    public void setNatMapping(NatMapping nat) {
        natMap = nat;
    }

    // Used by RuleEngine to discover resources that must be initialized
    // or preserved. Not all NatRules have NatTargets (e.g. reverse nats). 
    public Set<NatTarget> getNatTargets() {
        return null;
    }
}
