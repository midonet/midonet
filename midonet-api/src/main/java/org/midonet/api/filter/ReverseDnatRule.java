/*
 * Copyright 2012 Midokura PTE LTD.
 */
package org.midonet.api.filter;

/**
 * Reverse DNAT rule DTO
 */
public class ReverseDnatRule extends NatRule {

    public ReverseDnatRule() {
        super();
    }

    public ReverseDnatRule(
            org.midonet.cluster.data.rules.ReverseNatRule rule) {
        super(rule);
        if (!rule.isDnat()) {
            throw new IllegalArgumentException("Invalid argument passed in.");
        }
    }

    @Override
    public String getType() {
        return RuleType.RevDNAT;
    }

    @Override
    public org.midonet.cluster.data.Rule toData () {
        org.midonet.cluster.data.rules.ReverseNatRule data =
                new org.midonet.cluster.data.rules.ReverseNatRule(
                        makeCondition(), getNatFlowAction(), true);
        super.setData(data);
        return data;
    }
}
