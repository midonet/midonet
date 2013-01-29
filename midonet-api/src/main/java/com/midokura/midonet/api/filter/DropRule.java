/*
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midonet.api.filter;

import com.midokura.midolman.rules.RuleResult;
import com.midokura.midonet.cluster.data.rules.LiteralRule;

/**
 * Drop rule DTO
 */
public class DropRule extends Rule {

    public DropRule() {
        super();
    }

    public DropRule(LiteralRule rule) {
        super(rule);
    }

    @Override
    public String getType() {
        return RuleType.Drop;
    }

    @Override
    public com.midokura.midonet.cluster.data.Rule toData () {
        LiteralRule data = new LiteralRule(makeCondition(),
                RuleResult.Action.DROP);
        super.setData(data);
        return data;
    }
}
