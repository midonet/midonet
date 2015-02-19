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

import org.midonet.midolman.TraceRequiredException;
import org.midonet.midolman.rules.RuleResult.Action;
import org.midonet.midolman.simulation.PacketContext;

public class TraceRule extends Rule {

    private final static Logger log =
        LoggerFactory.getLogger(TraceRule.class);

    public TraceRule(Condition condition) {
        // never actually sets the result action
        super(condition, Action.CONTINUE);
    }

    // Default constructor for the Jackson deserialization.
    // This constructor is also used by ZoomConvert.
    public TraceRule() {
        super();
    }

    public TraceRule(Condition condition, UUID chainId,
                     int position) {
        super(condition, Action.CONTINUE, chainId, position);
    }

    @Override
    public void apply(PacketContext pktCtx, RuleResult res, UUID ownerId) {
        if (!pktCtx.tracingEnabled()) {
            throw TraceRequiredException.instance();
        }
        // else do nothing, tracing has already been enabled for the packet
    }

    @Override
    public int hashCode() {
        return 11 * super.hashCode() + "TraceRule".hashCode();
    }

    @Override
    public boolean equals(Object other) {
        if (this == other)
            return true;
        if (!(other instanceof TraceRule))
            return false;
        return super.equals(other);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("TraceRule [");
        sb.append(super.toString());
        sb.append("]");
        return sb.toString();
    }
}
