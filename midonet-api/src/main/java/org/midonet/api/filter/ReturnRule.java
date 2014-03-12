/*
 * Copyright 2012 Midokura PTE LTD.
 */
package org.midonet.api.filter;

import org.midonet.midolman.rules.RuleResult;
import org.midonet.cluster.data.rules.LiteralRule;

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
    public LiteralRule toData () {
        LiteralRule data = new LiteralRule(makeCondition(),
                RuleResult.Action.RETURN);
        super.setData(data);
        return data;
    }
}
