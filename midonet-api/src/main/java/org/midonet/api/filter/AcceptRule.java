/*
 * Copyright 2012 Midokura PTE LTD.
 */
package org.midonet.api.filter;

import org.midonet.midolman.rules.RuleResult;
import org.midonet.cluster.data.rules.LiteralRule;

/**
 * Accept rule DTO
 */
public class AcceptRule extends Rule {

    public AcceptRule() {
        super();
    }

    public AcceptRule(LiteralRule rule) {
        super(rule);
    }

    @Override
    public String getType() {
        return RuleType.Accept;
    }

    @Override
    public LiteralRule toData () {
        LiteralRule data = new LiteralRule(makeCondition(),
                RuleResult.Action.ACCEPT);
        super.setData(data);
        return data;
    }
}
