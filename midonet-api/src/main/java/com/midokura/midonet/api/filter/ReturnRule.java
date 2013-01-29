/*
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midonet.api.filter;

import com.midokura.midolman.rules.RuleResult;
import com.midokura.midonet.cluster.data.rules.LiteralRule;

/**
 * Return rule DTO
 */
public class ReturnRule extends Rule {

    public ReturnRule() {
        super();
    }

    public ReturnRule(LiteralRule rule) {
        super(rule);
    }

    @Override
    public String getType() {
        return RuleType.Return;
    }

    @Override
    public com.midokura.midonet.cluster.data.Rule toData () {
        LiteralRule data = new LiteralRule(makeCondition(),
                RuleResult.Action.RETURN);
        super.setData(data);
        return data;
    }
}
