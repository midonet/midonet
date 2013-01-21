/*
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midonet.cluster.data.rules;

import com.midokura.midolman.rules.Condition;
import com.midokura.midolman.rules.RuleResult;
import com.midokura.midonet.cluster.data.Rule;

import java.util.UUID;

/**
 * Basic abstraction for a literal rule
 */
public class LiteralRule extends Rule<Rule.Data, LiteralRule> {

    public LiteralRule(Condition condition, RuleResult.Action action) {
        this(null, condition, action, new Data());
    }

    public LiteralRule(UUID uuid, Condition condition, RuleResult.Action action,
                       Rule.Data ruleData){
        super(uuid, condition, ruleData);
        setAction(action);
    }

    @Override
    public LiteralRule setAction(RuleResult.Action action) {
        if (action != RuleResult.Action.ACCEPT
                && action != RuleResult.Action.DROP
                && action != RuleResult.Action.REJECT
                && action != RuleResult.Action.RETURN)
            throw new IllegalArgumentException("A literal rule's action "
                    + "must be one of: ACCEPT, DROP, REJECT or RETURN.");
        super.setAction(action);
        return self();
    }

    @Override
    protected LiteralRule self() {
        return this;
    }
}