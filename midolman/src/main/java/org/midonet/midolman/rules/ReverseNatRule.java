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

import org.midonet.midolman.rules.RuleResult.Action;
import org.midonet.midolman.simulation.PacketContext;

public class ReverseNatRule extends NatRule {

    public ReverseNatRule(Condition condition, Action action, boolean dnat) {
        super(condition, action, dnat);
    }

    // default constructor for the JSON serialization.
    public ReverseNatRule() {
        super();
    }

    public ReverseNatRule(Condition condition, Action action, UUID chainId,
                          int position, boolean dnat) {
        super(condition, action, chainId, position, dnat);
    }

    @Override
    public void apply(PacketContext pktCtx, RuleResult res, UUID ownerId) {
        boolean reversed = dnat ? applyReverseDnat(pktCtx, ownerId)
                                : applyReverseSnat(pktCtx, ownerId);

        if (reversed)
            res.action = action;
    }

    protected boolean applyReverseDnat(PacketContext pktCtx, UUID ownerId) {
        return pktCtx.state().reverseDnat(ownerId);
    }

    protected boolean applyReverseSnat(PacketContext pktCtx, UUID ownerId) {
        return pktCtx.state().reverseSnat(ownerId);
    }

    @Override
    public int hashCode() {
        return super.hashCode() * 29 + "ReverseNatRule".hashCode();
    }

    @Override
    public boolean equals(Object other) {
        if (this == other)
            return true;
        if (!(other instanceof ReverseNatRule))
            return false;
        return super.equals(other);
    }

    @Override
    public String toString() {
        return "ReverseNatRule [" + super.toString() + "]";
    }
}
