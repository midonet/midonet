package com.midokura.midolman.rules;

import com.midokura.midolman.layer4.NatMapping;

import com.midokura.midolman.rules.RuleResult.Action;

public abstract class NatRule extends Rule {
    // The NatMapping is irrelevant to the hashCode, equals and serialization.
    protected transient NatMapping natMap;
    public boolean dnat;

    public NatRule(Condition condition, Action action, boolean dnat) {
        super(condition, action);
        this.dnat = dnat;
        if (action != Action.ACCEPT && action != Action.CONTINUE
                && action != Action.RETURN)
            throw new IllegalArgumentException("A nat rule's action "
                    + "must be one of: ACCEPT, CONTINUE, or RETURN.");
    }

	// Default constructor for the Jackson deserialization.
	public NatRule() { super(); }

    public void setNatMapping(NatMapping nat) {
        natMap = nat;
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
}
