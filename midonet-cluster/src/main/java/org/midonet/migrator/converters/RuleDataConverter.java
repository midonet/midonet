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

package org.midonet.migrator.converters;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.midonet.cluster.data.rules.LiteralRule;
import org.midonet.cluster.data.rules.ReverseNatRule;
import org.midonet.cluster.rest_api.models.AcceptRule;
import org.midonet.cluster.rest_api.models.DropRule;
import org.midonet.cluster.rest_api.models.ForwardDnatRule;
import org.midonet.cluster.rest_api.models.ForwardNatRule;
import org.midonet.cluster.rest_api.models.ForwardSnatRule;
import org.midonet.cluster.rest_api.models.JumpRule;
import org.midonet.cluster.rest_api.models.NatRule;
import org.midonet.cluster.rest_api.models.RejectRule;
import org.midonet.cluster.rest_api.models.ReturnRule;
import org.midonet.cluster.rest_api.models.ReverseDnatRule;
import org.midonet.cluster.rest_api.models.ReverseSnatRule;
import org.midonet.cluster.rest_api.models.Rule;
import org.midonet.cluster.rest_api.models.TraceRule;
import org.midonet.midolman.rules.RuleResult;

public class RuleDataConverter {

    public static Rule fromData(org.midonet.cluster.data.Rule<?, ?> data) {

        Rule dto = null;
        if (data instanceof LiteralRule) {
            switch(data.getAction()) {
                case ACCEPT: dto = new AcceptRule(); break;
                case DROP: dto = new DropRule(); break;
                case REJECT: dto = new RejectRule(); break;
                case RETURN: dto = new ReturnRule(); break;
            }
        } else if (data instanceof org.midonet.cluster.data.rules.JumpRule) {
            org.midonet.cluster.data.rules.JumpRule jumpData =
                (org.midonet.cluster.data.rules.JumpRule)data;

            dto = new JumpRule(jumpData.getJumpToChainId(),
                               jumpData.getJumpToChainName());
        } else if (data instanceof org.midonet.cluster.data.rules.TraceRule) {
            org.midonet.cluster.data.rules.TraceRule typedData =
                (org.midonet.cluster.data.rules.TraceRule)data;
            TraceRule tr = new TraceRule();
            tr.limit = typedData.getLimit();
            tr.requestId = typedData.getRequestId();
            dto = tr;
        } else if (data instanceof
                   org.midonet.cluster.data.rules.ForwardNatRule) {
            org.midonet.cluster.data.rules.ForwardNatRule typedData
                = (org.midonet.cluster.data.rules.ForwardNatRule) data;
            if (typedData.isDnat()) {
                ForwardDnatRule fdnDto = new ForwardDnatRule();
                fdnDto.natTargets = makeTargetsFromRule(typedData);
                dto = fdnDto;
            } else {
                ForwardSnatRule fsnDto = new ForwardSnatRule();
                fsnDto.natTargets = makeTargetsFromRule(typedData);
                dto = fsnDto;
            }
        } else if (data instanceof ReverseNatRule) { // in o.m.cluster.data
            ReverseNatRule typedData = (ReverseNatRule) data;
            if (typedData.isDnat()) {
                dto = new ReverseDnatRule();
            } else {
                dto = new ReverseSnatRule();
            }
        }

        if (dto == null) {
            throw new UnsupportedOperationException(
                "Cannot instantiate this rule type.");
        }

        dto.action = fromSimAction(data.getAction());
        dto.id = UUID.fromString(data.getId().toString());
        dto.chainId = data.getChainId();
        dto.position = data.getPosition();
        dto.meterName = data.getMeterName();

        if (dto instanceof NatRule)
            ((NatRule)dto).flowAction = dto.action.toString().toLowerCase();

        ConditionDataConverter.fillFromSimulationData(dto, data.getCondition());

        return dto;
    }

    private static Rule.RuleAction fromSimAction(RuleResult.Action a) {
        if (a == null) {
            return null;
        } else if (RuleResult.Action.ACCEPT.equals(a)) {
            return Rule.RuleAction.ACCEPT;
        } else if (RuleResult.Action.CONTINUE.equals(a)){
            return Rule.RuleAction.CONTINUE;
        } else if (RuleResult.Action.DROP.equals(a)) {
            return Rule.RuleAction.DROP;
        } else if (RuleResult.Action.JUMP.equals(a)) {
            return Rule.RuleAction.JUMP;
        } else if (RuleResult.Action.REJECT.equals(a)) {
            return Rule.RuleAction.REJECT;
        } else if (RuleResult.Action.RETURN.equals(a)) {
            return Rule.RuleAction.RETURN;
        } else {
            throw new IllegalArgumentException("Unknown rule action " + a);
        }
    }

    protected static ForwardNatRule.NatTarget[] makeTargetsFromRule(
        org.midonet.cluster.data.rules.ForwardNatRule natRule) {
        Set<org.midonet.midolman.rules.NatTarget> ruleTargets = natRule
            .getTargets();

        List<org.midonet.cluster.rest_api.models.ForwardNatRule.NatTarget>
            targets = new ArrayList<>(ruleTargets.size());

        for (org.midonet.midolman.rules.NatTarget natTarget : ruleTargets) {
            ForwardNatRule.NatTarget target = new ForwardNatRule.NatTarget();

            target.addressFrom = natTarget.nwStart.toString();
            target.addressTo = natTarget.nwEnd.toString();

            target.portFrom = natTarget.tpStart;
            target.portTo = natTarget.tpEnd;

            targets.add(target);
        }

        return targets.toArray(new ForwardNatRule.NatTarget[ruleTargets.size()]);
    }

}
