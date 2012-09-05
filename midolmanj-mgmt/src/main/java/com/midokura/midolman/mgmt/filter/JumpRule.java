/*
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.mgmt.filter;

import java.util.UUID;

/**
 * Jump rule DTO
 */
public class JumpRule extends Rule {

    private String jumpChainName = null;
    private UUID jumpChainId;

    public JumpRule() {
        super();
    }

    public JumpRule(
            com.midokura.midonet.cluster.data.rules.JumpRule rule) {
        super(rule);
        this.jumpChainId = rule.getJumpToChainId();
        this.jumpChainName = rule.getJumpToChainName();
    }

    @Override
    public String getType() {
        return RuleType.Jump;
    }

    /**
     * @return the jumpChainName
     */
    public String getJumpChainName() {
        return jumpChainName;
    }

    /**
     * @param jumpChainName
     *            the jumpChainName to set
     */
    public void setJumpChainName(String jumpChainName) {
        this.jumpChainName = jumpChainName;
    }

    /**
     * @return the jumpChainId
     */
    public UUID getJumpChainId() {
        return jumpChainId;
    }

    /**
     * @param jumpChainId
     *            the jumpChainName to set
     */
    public void setJumpChainId(UUID jumpChainId) {
        this.jumpChainId = jumpChainId;
    }

    @Override
    public com.midokura.midonet.cluster.data.Rule toData () {
        com.midokura.midonet.cluster.data.rules.JumpRule data =
                new com.midokura.midonet.cluster.data.rules.JumpRule(
                        makeCondition())
                .setJumpToChainId(this.jumpChainId)
                .setJumpToChainName(this.jumpChainName);
        super.setData(data);
        return data;
    }
}
