/*
 * Copyright 2011 Midokura KK
 */

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

    // Setter for Jackson serialization
    @SuppressWarnings("unused")
	private void setCondition(Condition cond) { this.condition = cond; }

    /**
     * If the packet specified by res.match matches this rule's condition,
     * apply the rule.
     *
     * @param flowMatch
     *            matches the packet that originally entered the datapath. It
     *            will NOT be modified by the rule chain.
     * @param inPortId
     * @param outPortId
     * @param res
     *            contains a match of the packet after all transformations
     *            preceding this rule. This may be modified.
     */
    public void process(MidoMatch flowMatch, UUID inPortId, UUID outPortId,
            RuleResult res, UUID ownerId) {
        if (condition.matches(inPortId, outPortId, res.match)) {
            apply(flowMatch, inPortId, outPortId, res, ownerId);
        }
    }

    public Condition getCondition() {
        return condition;
    }

    /**
     * Apply this rule to the packet specified by res.match.
     *
     * @param flowMatch
     *            matches the packet that originally entered the datapath. It
     *            will NOT be modified by the rule chain.
     * @param inPortId
     * @param outPortId
     * @param res
     *            contains a match of the packet after all transformations
     *            preceding this rule. This may be modified.
     * @param ownerId
     *            UUID of the element using this chain.
     */
    protected abstract void apply(MidoMatch flowMatch, UUID inPortId,
            UUID outPortId, RuleResult res, UUID ownerId);

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
        StringBuilder sb = new StringBuilder();
        sb.append("condition=").append(condition);
        sb.append(", action=").append(action);
        sb.append(", chainId=").append(chainId);
        sb.append(", position=").append(position);
        return sb.toString();
    }
}
