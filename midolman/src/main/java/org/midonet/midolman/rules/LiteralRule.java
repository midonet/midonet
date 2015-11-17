/*
 * Copyright 2015 Midokura SARL
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

public class LiteralRule extends Rule {

    public LiteralRule(Condition condition, Action action) {
        super(condition, action);
        if (action != Action.ACCEPT && action != Action.DROP
                && action != Action.REJECT && action != Action.RETURN)
            throw new IllegalArgumentException("A literal rule's action "
                    + "must be one of: ACCEPT, DROP, REJECT or RETURN.");
    }

    // Default constructor for the Jackson deserialization.
    // This constructor is also used by ZoomConvert.
    public LiteralRule() {
        super();
    }

    public LiteralRule(Condition condition, Action action, UUID chainId) {
        super(condition, action, chainId);
        if (action != Action.ACCEPT && action != Action.DROP
                && action != Action.REJECT && action != Action.RETURN) {
            throw new IllegalArgumentException("A literal rule's action "
                       + "must be one of: ACCEPT, DROP, REJECT or RETURN.");
        }
    }

    @Override
    protected boolean apply(PacketContext pktCtx) {
        pktCtx.jlog().debug("Packet matched literal rule with action {}", action);
        return true;
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
}
