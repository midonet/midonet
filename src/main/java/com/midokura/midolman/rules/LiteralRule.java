package com.midokura.midolman.rules;

import java.util.UUID;

public class LiteralRule extends Rule {

    protected Action action;

    public LiteralRule(Condition condition, Action action) {
        super(condition);
        this.action = action;
        if(action != Action.ACCEPT && action != Action.DROP &&
                action != Action.REJECT && action != Action.RETURN)
            throw new IllegalArgumentException("A literal rule's action " +
            		"must be one of: ACCEPT, DROP, REJECT or RETURN.");
    }

    @Override
    protected void apply(UUID inPortId, UUID outPortId, RuleResult res) {
        res.action = action;
    }

    @Override
    public int hashCode() {
        int hash = super.hashCode();
        return 31*hash + action.hashCode();
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof DnatRule)) return false;
        if (!super.equals(other))
            return false;
        LiteralRule r = (LiteralRule)other;
        return action.equals(r.action);
    }
}
