/*
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midonet.api.filter;

import com.midokura.midolman.rules.RuleResult;
import com.midokura.midonet.cluster.data.rules.LiteralRule;

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
    public com.midokura.midonet.cluster.data.Rule toData () {
        LiteralRule data = new LiteralRule(makeCondition(),
                RuleResult.Action.ACCEPT);
        super.setData(data);
        return data;
    }
}
