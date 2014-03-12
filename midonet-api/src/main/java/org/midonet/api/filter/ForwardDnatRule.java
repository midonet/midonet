/*
 * Copyright 2012 Midokura PTE LTD.
 */
package org.midonet.api.filter;

/**
 * Forward DNAT rule DTO
 */
public class ForwardDnatRule extends ForwardNatRule {

    public ForwardDnatRule() {
        super();
    }

    public ForwardDnatRule(
            org.midonet.cluster.data.rules.ForwardNatRule rule) {
        super(rule);
        if (!rule.isDnat()) {
            throw new IllegalArgumentException("Invalid argument passed in.");
        }
    }

    @Override
    public String getType() {
        return RuleType.DNAT;
    }

    @Override
    public org.midonet.cluster.data.rules.ForwardNatRule toData () {
        org.midonet.cluster.data.rules.ForwardNatRule data =
                new org.midonet.cluster.data.rules.ForwardNatRule(
                        makeCondition(), getNatFlowAction(),
                        makeTargetsForRule(), true);
        super.setData(data);
        return data;
    }
}
