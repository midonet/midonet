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
import org.midonet.midolman.rules.NatTarget;
import org.midonet.midolman.rules.RuleResult;

import java.util.Set;
import java.util.UUID;

/**
 * Basic abstraction for a reverse NAT rule
 */
public class ReverseNatRule
        extends NatRule<NatRule.Data, ReverseNatRule> {

    public ReverseNatRule(Condition condition, RuleResult.Action action,
                          boolean isDnat) {
        this(null, condition, action, isDnat, new Data());
    }

    public ReverseNatRule(UUID uuid, Condition condition,
                          RuleResult.Action action, boolean isDnat,
                          NatRule.Data ruleData){
        super(uuid, condition, action, isDnat, ruleData);
    }

    @Override
    protected ReverseNatRule self() {
        return this;
    }
}
