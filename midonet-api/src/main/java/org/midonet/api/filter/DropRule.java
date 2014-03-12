/*
 * Copyright 2012 Midokura PTE LTD.
 */
package org.midonet.api.filter;

import org.midonet.midolman.rules.RuleResult;
import org.midonet.cluster.data.rules.LiteralRule;

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
    public LiteralRule toData () {
        LiteralRule data = new LiteralRule(makeCondition(),
                RuleResult.Action.DROP);
        super.setData(data);
        return data;
    }
}
