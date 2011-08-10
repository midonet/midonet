package com.midokura.midolman.rules;

import java.util.UUID;

public class JumpRule extends Rule {

    String jumpToChain;

    public JumpRule(Condition condition, String jumpToChain) {
        super(condition, null);
        this.jumpToChain = jumpToChain;
    }

    @Override
    public void apply(UUID inPortId, UUID outPortId, RuleResult res) {
        res.action = Action.JUMP;
        res.jumpToChain = jumpToChain;
    }

    @Override
    public int hashCode() {
        int hash = super.hashCode();
        return 31*hash + jumpToChain.hashCode();
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof JumpRule)) return false;
        if (!super.equals(other))
            return false;
        JumpRule r = (JumpRule)other;
        return jumpToChain.equals(r.jumpToChain);
    }
}
