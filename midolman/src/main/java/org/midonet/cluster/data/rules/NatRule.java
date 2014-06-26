/*
 * Copyright 2012 Midokura PTE LTD.
 */
package org.midonet.cluster.data.rules;

import org.midonet.midolman.rules.Condition;
import org.midonet.midolman.rules.RuleResult;
import org.midonet.cluster.data.Rule;

import java.util.UUID;

/**
 * Basic abstraction for a NAT rule
 */
public abstract class NatRule
        <RuleData extends NatRule.Data, Self
                extends NatRule<RuleData, Self>>
        extends Rule<RuleData, Self>  {

    protected NatRule(UUID uuid, Condition condition, RuleResult.Action action,
                      boolean isDnat, RuleData ruleData){
        super(uuid, condition, ruleData);
        setAction(action);
        getData().dnat = isDnat;
    }

    @Override
    public Self setAction(RuleResult.Action action) {
        if (!action.equals(RuleResult.Action.ACCEPT)
                && !action.equals(RuleResult.Action.CONTINUE)
                && !action.equals(RuleResult.Action.RETURN))
            throw new IllegalArgumentException("A nat rule's action "
                    + "must be one of: ACCEPT, CONTINUE, or RETURN.");
        super.setAction(action);
        return self();
    }

    public boolean isDnat() {
        return getData().dnat;
    }

    public NatRule<?,?> setIsDnat(boolean isDnat) {
        getData().dnat = isDnat;
        return self();
    }

    public static class Data extends Rule.Data {

        public boolean dnat;

        @Override
        public int hashCode() {
            return super.hashCode() * 11 + (dnat ? 1231 : 1237);
        }

        @Override
        public boolean equals(Object other) {
            if (this == other)
                return true;
            if (!(other instanceof Data))
                return false;
            if (!super.equals(other))
                return false;
            return dnat == ((Data) other).dnat;
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append(super.toString());
            sb.append(", dnat=").append(dnat);
            return sb.toString();
        }
    }
}
