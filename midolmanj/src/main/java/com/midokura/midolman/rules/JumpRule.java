/*
 * Copyright 2011 Midokura KK
 */

package com.midokura.midolman.rules;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.midolman.layer4.NatMapping;
import com.midokura.midolman.rules.RuleResult.Action;


public class JumpRule extends Rule {

    private final static Logger log = LoggerFactory.getLogger(JumpRule.class);
    private static final long serialVersionUID = -7212783590950701193L;
    public UUID jumpToChainID;
    public String jumpToChainName;

    public JumpRule(
            Condition condition, UUID jumpToChainID, String jumpToChainName) {
        super(condition, null);
        this.jumpToChainID = jumpToChainID;
        this.jumpToChainName = jumpToChainName;
    }

    // Default constructor for the Jackson deserialization.
    public JumpRule() {
        super();
    }

    public JumpRule(Condition condition, UUID jumpToChainID,
                    String jumpToChainName, UUID chainId, int position) {
        super(condition, null, chainId, position);
        this.jumpToChainID = jumpToChainID;
        this.jumpToChainName = jumpToChainName;
    }

    @Override
    public void apply(Object flowCookie, RuleResult res,
                      NatMapping natMapping) {
        res.action = Action.JUMP;
        res.jumpToChain = jumpToChainID;
        log.debug("Rule evaluation jumping to chain {} with ID {}.",
                jumpToChainName, jumpToChainID);
    }

    @Override
    public int hashCode() {
        return super.hashCode() * 31 + jumpToChainID.hashCode();
    }

    @Override
    public boolean equals(Object other) {
        if (this == other)
            return true;
        if (!(other instanceof JumpRule))
            return false;
        if (!super.equals(other))
            return false;
        return jumpToChainID.equals(((JumpRule) other).jumpToChainID);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("JumpRule [");
        sb.append(super.toString());
        sb.append(", jumpToChainName=").append(jumpToChainName);
        sb.append(", jumpToChainID=").append(jumpToChainID);
        sb.append("]");
        return sb.toString();
    }
}
