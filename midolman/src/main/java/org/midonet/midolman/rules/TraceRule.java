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

import java.util.Objects;
import java.util.UUID;

import org.midonet.cluster.data.ZoomClass;
import org.midonet.cluster.data.ZoomField;
import org.midonet.cluster.data.ZoomOneOf;
import org.midonet.cluster.models.Topology;
import org.midonet.midolman.TraceRequiredException;
import org.midonet.midolman.rules.RuleResult.Action;
import org.midonet.midolman.simulation.PacketContext;

@ZoomClass(clazz = Topology.Rule.class, factory = TraceRule.RuleFactory.class)
@ZoomOneOf(name = "trace_rule_data")
public class TraceRule extends Rule {

    @ZoomField(name = "trace_request_id")
    private UUID requestId;

    @ZoomField(name = "limit")
    private long limit;

    private long hits;

    public TraceRule(UUID requestId, Condition condition, long limit) {
        // never actually sets the result action
        super(condition, Action.CONTINUE);
        this.requestId = requestId;
        this.limit = limit;
        this.hits = 0;
    }

    // Default constructor for the Jackson deserialization.
    // This constructor is also used by ZoomConvert.
    public TraceRule() {
        super();
    }

    public TraceRule(UUID requestId, Condition condition, long limit,
                     UUID chainId) {
        super(condition, Action.CONTINUE, chainId);
        this.requestId = requestId;
        this.limit = limit;
        this.hits = 0;
    }

    public UUID getRequestId() {
        return requestId;
    }

    public long getLimit() {
        return limit;
    }

    @Override
    protected boolean apply(PacketContext pktCtx) {
        if (!pktCtx.tracingEnabled(requestId) && hits < limit) {
            if (pktCtx.enableTracing(requestId)) {
                hits++;
            }
            throw TraceRequiredException.instance();
        }
        return true;
    }

    @Override
    public int hashCode() {
        return 11 * super.hashCode()
            + Objects.hash(requestId.hashCode(), limit);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other)
            return true;
        if (!(other instanceof TraceRule))
            return false;
        return super.equals(other)
            && this.requestId == ((TraceRule)other).requestId
            && this.limit == ((TraceRule)other).limit;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("TraceRule [");
        sb.append(super.toString());
        sb.append(", requestId=").append(requestId);
        sb.append(", limit=").append(limit);
        sb.append(", hits=").append(hits);
        sb.append("]");
        return sb.toString();
    }
}
