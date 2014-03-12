/*
 * Copyright 2012 Midokura PTE LTD.
 */
package org.midonet.api.filter;

import org.midonet.cluster.data.rules.ReverseNatRule;

/**
 * Reverse SNAT rule DTO
 */
public class ReverseSnatRule extends NatRule {

    public ReverseSnatRule() {
        super();
    }

    public ReverseSnatRule(
            org.midonet.cluster.data.rules.ReverseNatRule rule) {
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
    public ReverseNatRule toData () {
        ReverseNatRule data =
                new ReverseNatRule(makeCondition(), getNatFlowAction(), false);
        super.setData(data);
        return data;
    }
}
