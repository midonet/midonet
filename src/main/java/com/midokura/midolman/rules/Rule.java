package com.midokura.midolman.rules;

import java.util.UUID;

import com.midokura.midolman.openflow.MidoMatch;
import com.midokura.midolman.rules.RuleResult.Action;

public abstract class Rule implements Comparable<Rule> {
    private Condition condition;
    public Action action;
    public UUID chainId;
    public int position;

    public Rule(Condition condition, Action action) {
        this(condition, action, null, -1);
    }

    public Rule(Condition condition, Action action, UUID chainId, int position) {
        this.condition = condition;
        this.action = action;
        this.chainId = chainId;
        this.position = position;
    }

    // Default constructor for the Jackson deserialization.
    public Rule() {
        super();
    }

    public void process(MidoMatch flowMatch, UUID inPortId, UUID outPortId,
            RuleResult res) {
        if (condition.matches(inPortId, outPortId, res.match)) {
            apply(flowMatch, inPortId, outPortId, res);
        }
    }

    public Condition getCondition() {
        return condition;
    }

    // Call process instead - it calls 'apply' if appropriate.
    public abstract void apply(MidoMatch flowMatch, UUID inPortId,
            UUID outPortId, RuleResult res);

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

    @Override
    public int compareTo(Rule other) {
        return this.position - other.position;
    }

    @Override
    public String toString() {
        return "Rule [condition=" + condition + ", action=" + action + ", chainId=" + chainId + ", position="
                + position + "]";
    }
}
