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
package org.midonet.api.filter;

import org.midonet.midolman.rules.RuleResult;

import javax.validation.constraints.NotNull;

/**
 * NAT rule DTO
 */
public abstract class NatRule extends Rule {

    @NotNull
    protected String flowAction;

    public NatRule() {
        super();
    }

    public NatRule(org.midonet.cluster.data.rules.NatRule<?, ?> rule) {
        super(rule);
        setFlowActionFromAction(rule.getAction());
    }

    /**
     * @return the flowAction
     */
    public String getFlowAction() {
        return flowAction;
    }

    /**
     * @param a
     *            the flowAction to set
     */
    public void setFlowActionFromAction(RuleResult.Action a) {
        switch (a) {
            case ACCEPT:
                flowAction = RuleType.Accept;
                break;
            case CONTINUE:
                flowAction = RuleType.Continue;
                break;
            case RETURN:
                flowAction = RuleType.Return;
                break;
            default:
                throw new IllegalArgumentException("Invalid action passed in.");
        }
    }

    public RuleResult.Action getNatFlowAction() {
        // ACCEPT, CONTINUE, RETURN
        if (flowAction.equals(RuleType.Accept)) {
            return RuleResult.Action.ACCEPT;
        } else if (flowAction.equals(RuleType.Continue)) {
            return RuleResult.Action.CONTINUE;
        } else if (flowAction.equals(RuleType.Return)) {
            return RuleResult.Action.RETURN;
        } else {
            throw new IllegalArgumentException("Invalid action passed in.");
        }

    }
}
