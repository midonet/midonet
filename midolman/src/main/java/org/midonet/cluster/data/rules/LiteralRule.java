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
import org.midonet.midolman.rules.RuleResult;
import org.midonet.cluster.data.Rule;

import java.util.UUID;

/**
 * Basic abstraction for a literal rule
 */
public class LiteralRule extends Rule<Rule.Data, LiteralRule> {

    public LiteralRule(Condition condition, RuleResult.Action action) {
        this(null, condition, action, new Data());
    }

    public LiteralRule(UUID uuid, Condition condition, RuleResult.Action action,
                       Rule.Data ruleData){
        super(uuid, condition, ruleData);
        setAction(action);
    }

    @Override
    public LiteralRule setAction(RuleResult.Action action) {
        if (action != RuleResult.Action.ACCEPT
                && action != RuleResult.Action.DROP
                && action != RuleResult.Action.REJECT
                && action != RuleResult.Action.RETURN)
            throw new IllegalArgumentException("A literal rule's action "
                    + "must be one of: ACCEPT, DROP, REJECT or RETURN.");
        super.setAction(action);
        return self();
    }

    @Override
    protected LiteralRule self() {
        return this;
    }
}
