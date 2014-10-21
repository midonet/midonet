/*
 * Copyright 2014 Midokura SARL
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.midonet.api.filter;

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
            org.midonet.cluster.data.rules.JumpRule rule) {
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
    public org.midonet.cluster.data.rules.JumpRule toData () {
        org.midonet.cluster.data.rules.JumpRule data =
                new org.midonet.cluster.data.rules.JumpRule(
                        makeCondition())
                .setJumpToChainId(this.jumpChainId)
                .setJumpToChainName(this.jumpChainName);
        super.setData(data);
        return data;
    }
}
