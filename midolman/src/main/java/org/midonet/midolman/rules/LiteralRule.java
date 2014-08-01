/*
 * Copyright (c) 2011 Midokura SARL, All Rights Reserved.
 */

package org.midonet.midolman.rules;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.midonet.midolman.rules.RuleResult.Action;
import org.midonet.midolman.simulation.PacketContext;

public class LiteralRule extends Rule {

    private final static Logger log =
        LoggerFactory.getLogger(LiteralRule.class);

    public LiteralRule(Condition condition, Action action) {
        super(condition, action);
        if (action != Action.ACCEPT && action != Action.DROP
                && action != Action.REJECT && action != Action.RETURN)
            throw new IllegalArgumentException("A literal rule's action "
                    + "must be one of: ACCEPT, DROP, REJECT or RETURN.");
    }

    // Default constructor for the Jackson deserialization.
    public LiteralRule() {
        super();
    }

    public LiteralRule(Condition condition, Action action, UUID chainId,
            int position) {
        super(condition, action, chainId, position);
        if (action != Action.ACCEPT && action != Action.DROP
                && action != Action.REJECT && action != Action.RETURN)
            throw new IllegalArgumentException("A literal rule's action "
                    + "must be one of: ACCEPT, DROP, REJECT or RETURN.");
    }

    @Override
    public void apply(PacketContext pktCtx, RuleResult res) {
        res.action = action;
        log.debug("Packet matched literal rule with action {}", action);
    }

    @Override
    public int hashCode() {
        return 11 * super.hashCode() + "LiteralRule".hashCode();
    }

    @Override
    public boolean equals(Object other) {
        if (this == other)
            return true;
        if (!(other instanceof LiteralRule))
            return false;
        return super.equals(other);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("LiteralRule [");
        sb.append(super.toString());
        sb.append("]");
        return sb.toString();
    }
}
