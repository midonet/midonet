/*
 * Copyright 2011 Midokura KK
 */

package com.midokura.midolman.rules;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.midolman.layer4.NatMapping;
import com.midokura.midolman.rules.RuleResult.Action;
import com.midokura.sdn.flows.PacketMatch;


public abstract class Rule implements Comparable<Rule> {
    private final static Logger log = LoggerFactory.getLogger(Rule.class);

    private Condition condition;
    public Action action;
    public UUID chainId;
    public int position;
    private Map<String, String> properties = new HashMap<String, String>();

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
     * If the packet specified by res.pmatch matches this rule's condition,
     * apply the rule.
     *
     * @param fwdInfo
     *            the PacketContext for the packet being processed
     * @param res
     *            contains a match of the packet after all transformations
     *            preceding this rule. This may be modified.
     * @param natMapping
     *            NAT state of the element using this chain.
     * @param isPortFilter
     *            whether the rule is being processed in a port filter context
     */
    public void process(ChainPacketContext fwdInfo, RuleResult res,
                        NatMapping natMapping, boolean isPortFilter) {
        if (condition.matches(fwdInfo, res.pmatch, isPortFilter)) {
            log.debug("Condition matched");
            apply(fwdInfo.getFlowCookie(), res, natMapping);
        }
    }

    public Condition getCondition() {
        return condition;
    }

    /**
     * Apply this rule to the packet specified by res.pmatch.
     *
     * @param flowCookie
     *            An object that uniquely identifies the packet that
     *            originally entered the datapath. It
     *            will NOT be modified by the rule chain.
     * @param res
     *            contains a match of the packet after all transformations
     *            preceding this rule. This may be modified.
     * @param natMapping
     *            NAT state of the element using this chain.
     */
    protected abstract void apply(Object flowCookie, RuleResult res,
                                  NatMapping natMapping);


    public Map<String, String> getProperties() {
        return properties;
    }

    public void setProperties(Map<String, String> properties) {
        this.properties = properties;
    }

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
