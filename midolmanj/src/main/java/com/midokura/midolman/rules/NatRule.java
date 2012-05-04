/*
 * Copyright 2011 Midokura KK
 */

package com.midokura.midolman.rules;

import java.util.UUID;

import com.midokura.midolman.rules.RuleResult.Action;

public abstract class NatRule extends Rule {
    public boolean dnat;

    public NatRule(Condition condition, Action action, boolean dnat) {
        super(condition, action);
        this.dnat = dnat;
        if (!action.equals(Action.ACCEPT) && !action.equals(Action.CONTINUE)
                && !action.equals(Action.RETURN))
            throw new IllegalArgumentException("A nat rule's action "
                    + "must be one of: ACCEPT, CONTINUE, or RETURN.");
    }

    // Default constructor for the Jackson deserialization.
    public NatRule() { super(); }

    public NatRule(Condition condition, Action action, UUID chainId,
            int position, boolean dnat) {
        super(condition, action, chainId, position);
        this.dnat = dnat;
        if (!action.equals(Action.ACCEPT) && !action.equals(Action.CONTINUE)
                && !action.equals(Action.RETURN))
            throw new IllegalArgumentException("A nat rule's action "
                    + "must be one of: ACCEPT, CONTINUE, or RETURN.");
    }

    @Override
    public int hashCode() {
        return super.hashCode() * 11 + (dnat ? 1231 : 1237);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other)
            return true;
        if (!(other instanceof NatRule))
            return false;
        if (!super.equals(other))
            return false;
        return dnat == ((NatRule) other).dnat;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(super.toString());
        sb.append(", dnat=").append(dnat);
        return sb.toString();
    }
}
