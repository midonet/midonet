/*
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midonet.api.filter;

/**
 * Forward SNAT rule DTO
 */
public class ForwardSnatRule extends ForwardNatRule {

    public ForwardSnatRule() {
        super();
    }

    public ForwardSnatRule(
            com.midokura.midonet.cluster.data.rules.ForwardNatRule rule) {
        super(rule);
        if (rule.isDnat()) {
            throw new IllegalArgumentException("Invalid argument passed in.");
        }
    }

    @Override
    public String getType() {
        return RuleType.SNAT;
    }

    @Override
    public com.midokura.midonet.cluster.data.Rule toData () {
        com.midokura.midonet.cluster.data.rules.ForwardNatRule data =
                new com.midokura.midonet.cluster.data.rules.ForwardNatRule(
                        makeCondition(), getNatFlowAction(),
                        makeTargetsForRule(), false);
        super.setData(data);
        return data;
    }
}
