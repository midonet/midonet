package com.midokura.midolman.rules;

import java.util.UUID;

public abstract class Rule {

    private Condition condition;
    protected Action action;

    public Rule(Condition condition, Action action) {
        this.condition = condition;
        this.action = action;
    }

    public void process(UUID inPortId, UUID outPortId, RuleResult res) {
        if (condition.matches(inPortId, outPortId, res.match)){
            apply(inPortId, outPortId, res);
        }
    }

    // Call process instead - it calls 'apply' if appropriate.
    public abstract void apply(UUID inPortId, UUID outPortId,
            RuleResult res);

    @Override
    public int hashCode() {
        return condition.hashCode();
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof Rule)) return false;
        Rule r = (Rule)other;
        return condition.equals(r.condition);
    }
}
