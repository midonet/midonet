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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.midonet.midolman.rules.RuleResult.Action;
import org.midonet.midolman.simulation.PacketContext;

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

    public JumpRule(UUID chainId, UUID jumpChainId,
                    String jumpChainName) {
        this(new Condition(), jumpChainId, jumpChainName);
        this.chainId = chainId;
    }

    @Override
    public void apply(PacketContext pktCtx, RuleResult res, UUID ownerId) {
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
