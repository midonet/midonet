/*
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midonet.api.filter;

/**
 * Reverse SNAT rule DTO
 */
public class ReverseSnatRule extends NatRule {

    public ReverseSnatRule() {
        super();
    }

    public ReverseSnatRule(
            com.midokura.midonet.cluster.data.rules.ReverseNatRule rule) {
        super(rule);
        if (rule.isDnat()) {
            throw new IllegalArgumentException("Invalid argument passed in.");
        }
    }

    @Override
    public String getType() {
        return RuleType.RevSNAT;
    }

    @Override
    public com.midokura.midonet.cluster.data.Rule toData () {
        com.midokura.midonet.cluster.data.rules.ReverseNatRule data =
                new com.midokura.midonet.cluster.data.rules.ReverseNatRule(
                        makeCondition(), getNatFlowAction(), false);
        super.setData(data);
        return data;
    }
}
