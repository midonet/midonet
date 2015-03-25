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
package org.midonet.cluster.data.rules;

import org.midonet.midolman.rules.Condition;
import org.midonet.midolman.rules.RuleResult;
import org.midonet.cluster.data.Rule;

import java.util.UUID;

/**
 * Rule for tracing
 */
public class TraceRule extends Rule<Rule.Data, TraceRule> {
    private final UUID requestId;
    private final long limit;

    public TraceRule(UUID requestId, Condition condition, long limit) {
        super(null, condition, new Data());
        this.requestId = requestId;
        this.limit = limit;
    }

    public UUID getRequestId() {
        return requestId;
    }

    public long getLimit() {
        return limit;
    }

    @Override
    public TraceRule setAction(RuleResult.Action action) {
        throw new IllegalArgumentException(
                "Cannot set an action on trace rule");
    }

    @Override
    protected TraceRule self() {
        return this;
    }
}
