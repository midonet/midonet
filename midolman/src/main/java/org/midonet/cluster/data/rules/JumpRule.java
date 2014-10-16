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
package org.midonet.cluster.data.rules;

import org.midonet.midolman.rules.Condition;
import org.midonet.cluster.data.Rule;

import java.util.UUID;

/**
 * Basic abstraction for a jump rule
 */
public class JumpRule extends Rule<JumpRule.Data, JumpRule> {

    public JumpRule(Condition condition) {
        this(null, condition, new Data());
    }

    public JumpRule(UUID uuid, Condition condition, JumpRule.Data ruleData){
        super(uuid, condition, ruleData);
    }

    @Override
    protected JumpRule self() {
        return this;
    }

    public UUID getJumpToChainId() {
        return getData().jumpToChainID;
    }

    public JumpRule setJumpToChainId(UUID chainId) {
        getData().jumpToChainID = chainId;
        return self();
    }

    public String getJumpToChainName() {
        return getData().jumpToChainName;
    }

    public JumpRule setJumpToChainName(String chainName) {
        getData().jumpToChainName = chainName;
        return self();
    }

    public static class Data extends Rule.Data {

        public UUID jumpToChainID;
        public String jumpToChainName;

        @Override
        public int hashCode() {
            return super.hashCode() * 31 + jumpToChainID.hashCode();
        }

        @Override
        public boolean equals(Object other) {
            if (this == other)
                return true;
            if (!(other instanceof Data))
                return false;
            if (!super.equals(other))
                return false;
            return jumpToChainID.equals(((Data) other).jumpToChainID);
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
}
