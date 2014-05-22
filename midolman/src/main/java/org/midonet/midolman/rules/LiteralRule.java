/*
 * Copyright 2011 Midokura KK
 */

package org.midonet.midolman.rules;

import java.util.UUID;

import org.midonet.cluster.data.neutron.SecurityGroupRule;
import org.midonet.packets.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.midonet.midolman.layer4.NatMapping;
import org.midonet.midolman.rules.RuleResult.Action;


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
    public void apply(ChainPacketContext fwdInfo, RuleResult res,
                      NatMapping natMapping) {
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

    // Useful factory methods
    public static Rule acceptRule(Condition cond, UUID chainId) {
        Rule cfg = new LiteralRule(cond, RuleResult.Action.ACCEPT);
        cfg.chainId = chainId;
        return cfg;
    }

    public static Rule dropRule(Condition cond, UUID chainId) {
        Rule cfg = new LiteralRule(cond, RuleResult.Action.DROP);
        cfg.chainId = chainId;
        return cfg;
    }

    public static Rule acceptRule(SecurityGroupRule sgRule, UUID chainId) {
        return acceptRule(new Condition(sgRule), chainId);
    }

    public static Rule acceptReturnFlowRule(UUID chainId) {
        Condition cond = new Condition();
        cond.matchReturnFlow = true;
        return acceptRule(cond, chainId);
    }

    public static Rule ipSpoofProtectionRule(IPSubnet subnet, UUID chainId) {
        Condition cond = new Condition(subnet);
        cond.nwSrcInv = true;
        return dropRule(cond, chainId);
    }

    public static Rule macSpoofProtectionRule(MAC macAddress, UUID chainId) {
        // MAC spoofing protection for in_chain
        Condition cond = new Condition(macAddress);
        cond.invDlSrc = true;
        return dropRule(cond, chainId);
    }

    public static Rule dropAllExceptArpRule(UUID chainId) {
        Condition cond = new Condition();
        cond.dlType = (int) ARP.ETHERTYPE;
        cond.invDlType = true;
        return dropRule(cond, chainId);
    }
}
