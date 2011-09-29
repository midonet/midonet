package com.midokura.midolman.rules;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.midolman.openflow.MidoMatch;
import com.midokura.midolman.rules.RuleResult.Action;

public class JumpRule extends Rule {

    private final static Logger log = LoggerFactory.getLogger(JumpRule.class);
    private static final long serialVersionUID = -7212783590950701193L;
    public String jumpToChain;

    public JumpRule(Condition condition, String jumpToChain) {
        super(condition, null);
        this.jumpToChain = jumpToChain;
    }

    // Default constructor for the Jackson deserialization.
    public JumpRule() {
        super();
    }

    public JumpRule(Condition condition, String jumpToChain, UUID chainId,
            int position) {
        super(condition, null, chainId, position);
        this.jumpToChain = jumpToChain;
    }

    @Override
    public void apply(MidoMatch flowMatch, UUID inPortId, UUID outPortId,
            RuleResult res) {
        res.action = Action.JUMP;
        res.jumpToChain = jumpToChain;
        log.debug("Rule evaluation jumping to chain {}.", jumpToChain);
    }

    @Override
    public int hashCode() {
        return super.hashCode() * 31 + jumpToChain.hashCode();
    }

    @Override
    public boolean equals(Object other) {
        if (this == other)
            return true;
        if (!(other instanceof JumpRule))
            return false;
        if (!super.equals(other))
            return false;
        return jumpToChain.equals(((JumpRule) other).jumpToChain);
    }
}
