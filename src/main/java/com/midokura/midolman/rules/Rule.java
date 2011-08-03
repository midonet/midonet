package com.midokura.midolman.rules;

import java.util.UUID;

public abstract class Rule {

    private Condition condition;

    public Rule(Condition condition) {
        this.condition = condition;
    }

    public void process(UUID inPortId, UUID outPortId, RuleResult res) {
        if (condition.matches(inPortId, outPortId, res.match)){
            apply(inPortId, outPortId, res);
        }
    }

    protected abstract void apply(UUID inPortId, UUID outPortId,
            RuleResult res);
}
