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
package org.midonet.api.filter;

import java.util.UUID;
import javax.validation.constraints.NotNull;

/**
 * Trace rule DTO
 */
public class TraceRule extends Rule {

    @NotNull
    private UUID requestId;

    public TraceRule() {
        super();
    }

    public TraceRule(org.midonet.cluster.data.rules.TraceRule rule) {
        super(rule);
        this.requestId = rule.getRequestId();
    }

    public UUID getRequestId() {
        return this.requestId;
    }

    @Override
    public String getType() {
        return RuleType.Trace;
    }

    @Override
    public org.midonet.cluster.data.rules.TraceRule toData () {
        org.midonet.cluster.data.rules.TraceRule data
            = new org.midonet.cluster.data.rules.TraceRule(requestId,
                                                           makeCondition());
        super.setData(data);
        return data;
    }
}
