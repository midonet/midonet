/*
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.mgmt.filter;

/**
 * Forward DNAT rule DTO
 */
public class ForwardDnatRule extends ForwardNatRule {

    public ForwardDnatRule() {
        super();
    }

    public ForwardDnatRule(
            com.midokura.midonet.cluster.data.rules.ForwardNatRule rule) {
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
    public com.midokura.midonet.cluster.data.Rule toData () {
        com.midokura.midonet.cluster.data.rules.ForwardNatRule data =
                new com.midokura.midonet.cluster.data.rules.ForwardNatRule(
                        makeCondition(), getNatFlowAction(),
                        makeTargetsForRule(), true);
        super.setData(data);
        return data;
    }
}
