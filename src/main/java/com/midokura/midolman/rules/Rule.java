package com.midokura.midolman.rules;

import java.io.Serializable;
import java.util.UUID;

public abstract class Rule implements Serializable {

    private static final long serialVersionUID = -5679026587128317121L;

    private Condition condition;
    protected Action action;

    public Rule(Condition condition, Action action) {
        this.condition = condition;
        this.action = action;
    }

    public void process(UUID inPortId, UUID outPortId, RuleResult res) {
        if (condition.matches(inPortId, outPortId, res.match)) {
            apply(inPortId, outPortId, res);
        }
    }

    // Call process instead - it calls 'apply' if appropriate.
    public abstract void apply(UUID inPortId, UUID outPortId, RuleResult res);

    @Override
    public int hashCode() {
        int hash = condition.hashCode() * 23;
        if (null != action)
            hash += action.hashCode();
        return hash;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other)
            return true;
        if (!(other instanceof Rule))
            return false;
        Rule r = (Rule) other;
        if (!condition.equals(r.condition))
            return false;
        if (null == action || null == r.action) {
            return action == r.action;
        } else {
            return action.equals(r.action);
        }
    }
}
