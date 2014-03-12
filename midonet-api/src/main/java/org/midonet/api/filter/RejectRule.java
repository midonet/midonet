/*
 * Copyright 2012 Midokura PTE LTD.
 */
package org.midonet.api.filter;

import org.midonet.midolman.rules.RuleResult;
import org.midonet.cluster.data.rules.LiteralRule;

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
    public LiteralRule toData () {
        LiteralRule data = new LiteralRule(makeCondition(),
                RuleResult.Action.REJECT);
        super.setData(data);
        return data;
    }
}
