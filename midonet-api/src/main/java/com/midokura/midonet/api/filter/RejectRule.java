/*
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midonet.api.filter;

import com.midokura.midolman.rules.RuleResult;
import com.midokura.midonet.cluster.data.rules.LiteralRule;

/**
 * Reject rule DTO
 */
public class RejectRule extends Rule {

    public RejectRule() {
        super();
    }

    public RejectRule(LiteralRule rule) {
        super(rule);
    }

    @Override
    public String getType() {
        return RuleType.Reject;
    }

    @Override
    public com.midokura.midonet.cluster.data.Rule toData () {
        LiteralRule data = new LiteralRule(makeCondition(),
                RuleResult.Action.REJECT);
        super.setData(data);
        return data;
    }
}
