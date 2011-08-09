package com.midokura.midolman.rules;

import java.util.UUID;

public class ReverseSnatRule extends Rule {

    public ReverseSnatRule(Condition condition) {
        super(condition);
    }

    @Override
    protected void apply(UUID inPortId, UUID outPortId, RuleResult res) {
        // TODO Auto-generated method stub
        
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof ReverseDnatRule)) return false;
        return super.equals(other);
    }
}
