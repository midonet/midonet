/*
 * Copyright 2012 Midokura PTE LTD.
 */
package org.midonet.cluster.data.rules;

import org.midonet.midolman.rules.Condition;
import org.midonet.midolman.rules.NatTarget;
import org.midonet.midolman.rules.RuleResult;

import java.util.Set;
import java.util.UUID;

/**
 * Basic abstraction for a reverse NAT rule
 */
public class ReverseNatRule
        extends NatRule<NatRule.Data, ReverseNatRule> {

    public ReverseNatRule(Condition condition, RuleResult.Action action,
                          boolean isDnat) {
        this(null, condition, action, isDnat, new Data());
    }

    public ReverseNatRule(UUID uuid, Condition condition,
                          RuleResult.Action action, boolean isDnat,
                          NatRule.Data ruleData){
        super(uuid, condition, action, isDnat, ruleData);
    }

    @Override
    protected ReverseNatRule self() {
        return this;
    }
}
