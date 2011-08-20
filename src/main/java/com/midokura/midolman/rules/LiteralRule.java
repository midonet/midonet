package com.midokura.midolman.rules;

import java.util.UUID;

import com.midokura.midolman.rules.RuleResult.Action;

public class LiteralRule extends Rule {

    private static final long serialVersionUID = -4902104131572973862L;

    public LiteralRule(Condition condition, Action action) {
        super(condition, action);
        if (action != Action.ACCEPT && action != Action.DROP
                && action != Action.REJECT && action != Action.RETURN)
            throw new IllegalArgumentException("A literal rule's action "
                    + "must be one of: ACCEPT, DROP, REJECT or RETURN.");
    }

    @Override
    public void apply(UUID inPortId, UUID outPortId, RuleResult res) {
        res.action = action;
    }

    @Override
    public int hashCode() {
        return 11 * super.hashCode() + "LiteralRule".hashCode();
    }

    @Override
    public boolean equals(Object other) {
        if (this == other)
            return true;
        if (!(other instanceof LiteralRule))
            return false;
        return super.equals(other);
    }
}
