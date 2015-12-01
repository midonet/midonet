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

package org.midonet.midolman.rules;

import java.util.UUID;

import com.google.protobuf.Message;

import org.midonet.cluster.data.ZoomField;
import org.midonet.cluster.data.ZoomOneOf;
import org.midonet.midolman.rules.RuleResult.Action;
import org.midonet.midolman.simulation.PacketContext;

@ZoomOneOf(name = "jump_rule_data")
public class JumpRule extends Rule {

    private static final long serialVersionUID = -7212783590950701193L;

    @ZoomField(name = "jump_chain_id")
    public UUID jumpToChainID;
    @ZoomField(name = "jump_chain_name")
    public String jumpToChainName;

    private RuleResult result;

    public JumpRule(Condition condition, UUID jumpToChainID,
                    String jumpToChainName) {
        super(condition, Action.JUMP);
        this.jumpToChainID = jumpToChainID;
        this.jumpToChainName = jumpToChainName;
    }

    // Default constructor for the Jackson deserialization.
    // This constructor is also needed by ZoomConvert.
    public JumpRule() {
        super();
        action = Action.JUMP;
    }

    public JumpRule(Condition condition, UUID jumpToChainID,
                    String jumpToChainName, UUID chainId) {
        super(condition, Action.JUMP, chainId);
        this.jumpToChainID = jumpToChainID;
        this.jumpToChainName = jumpToChainName;
        result = RuleResult.jumpResult(jumpToChainID);
    }

    public JumpRule(UUID chainId, UUID jumpChainId,
                    String jumpChainName) {
        this(new Condition(), jumpChainId, jumpChainName);
        this.chainId = chainId;
    }

    @Override
    public void afterFromProto(Message proto) {
        super.afterFromProto(proto);
        result = RuleResult.jumpResult(jumpToChainID);
    }

    @Override
    protected RuleResult onSuccess() {
        if (result == null) //TODO: Remove this after v2
            result = RuleResult.jumpResult(jumpToChainID);
        return result;
    }

    @Override
    protected boolean apply(PacketContext pktCtx) {
        pktCtx.jlog().debug("Rule evaluation jumping to chain {} with ID {}.",
                jumpToChainName, jumpToChainID);
        return true;
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
