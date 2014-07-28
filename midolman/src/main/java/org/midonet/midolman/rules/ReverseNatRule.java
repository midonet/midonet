/*
 * Copyright (c) 2011 Midokura SARL, All Rights Reserved.
 */

package org.midonet.midolman.rules;

import java.util.UUID;

import org.midonet.midolman.rules.RuleResult.Action;
import org.midonet.midolman.simulation.PacketContext;

public class ReverseNatRule extends NatRule {

    public ReverseNatRule(Condition condition, Action action, boolean dnat) {
        super(condition, action, dnat);
    }

    // default constructor for the JSON serialization.
    public ReverseNatRule() {
        super();
    }

    public ReverseNatRule(Condition condition, Action action, UUID chainId,
                          int position, boolean dnat) {
        super(condition, action, chainId, position, dnat);
    }

    @Override
    public void apply(PacketContext pktCtx, RuleResult res) {
        boolean reversed = dnat ? applyReverseDnat(pktCtx)
                                : applyReverseSnat(pktCtx);

        if (reversed)
            res.action = action;
    }

    protected boolean applyReverseDnat(PacketContext pktCtx) {
        return pktCtx.state().reverseDnat();
    }

    protected boolean applyReverseSnat(PacketContext pktCtx) {
        return pktCtx.state().reverseSnat();
    }

    @Override
    public int hashCode() {
        return super.hashCode() * 29 + "ReverseNatRule".hashCode();
    }

    @Override
    public boolean equals(Object other) {
        if (this == other)
            return true;
        if (!(other instanceof ReverseNatRule))
            return false;
        return super.equals(other);
    }

    @Override
    public String toString() {
        return "ReverseNatRule [" + super.toString() + "]";
    }
}
