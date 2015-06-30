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

package org.midonet.cluster.rest_api.conversion;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
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
import org.midonet.cluster.rest_api.models.MirrorRule;
import org.midonet.cluster.rest_api.models.RejectRule;
import org.midonet.cluster.rest_api.models.ReturnRule;
import org.midonet.cluster.rest_api.models.ReverseDnatRule;
import org.midonet.cluster.rest_api.models.ReverseSnatRule;
import org.midonet.cluster.rest_api.models.Rule;
import org.midonet.cluster.rest_api.models.TraceRule;
import org.midonet.midolman.rules.RuleResult;
import org.midonet.packets.IPv4Addr;

import static org.midonet.cluster.rest_api.conversion.ConditionDataConverter.makeCondition;

public class RuleDataConverter {

    public static Rule fromData(org.midonet.cluster.data.Rule<?, ?> data,
                                URI baseUri) {

        Rule dto = null;
        if (data instanceof LiteralRule) {
            switch(data.getAction()) {
                case ACCEPT: dto = new AcceptRule(); break;
                case DROP: dto = new DropRule(); break;
                case REJECT: dto = new RejectRule(); break;
                case RETURN: dto = new ReturnRule(); break;
            }
        } else if (data instanceof org.midonet.cluster.data.rules.MirrorRule) {
            org.midonet.cluster.data.rules.MirrorRule mirrorData =
                    (org.midonet.cluster.data.rules.MirrorRule) data;
            dto = new MirrorRule(mirrorData.getDstPotId());
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
                dto = new ForwardDnatRule();
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
        dto.setBaseUri(baseUri);

        ConditionDataConverter.fillFromSimulationData(dto, data.getCondition());

        return dto;
    }

    private static RuleResult.Action toSimAction(Rule.RuleAction a) {
        if (a == null) {
            return null;
        } else if (Rule.RuleAction.ACCEPT.equals(a)) {
            return RuleResult.Action.ACCEPT;
        } else if (Rule.RuleAction.CONTINUE.equals(a)) {
            return RuleResult.Action.CONTINUE;
        } else if (Rule.RuleAction.DROP.equals(a)) {
            return RuleResult.Action.DROP;
        } else if (Rule.RuleAction.JUMP.equals(a)) {
            return RuleResult.Action.JUMP;
        } else if (Rule.RuleAction.REJECT.equals(a)) {
            return RuleResult.Action.REJECT;
        } else if (Rule.RuleAction.RETURN.equals(a)) {
            return RuleResult.Action.RETURN;
        } else {
            throw new IllegalArgumentException("Unknown rule action " + a);
        }
    }

    private static RuleResult.Action getNatFlowAction(String flowAction) {
        switch (flowAction) {
            case Rule.Accept:
                return RuleResult.Action.ACCEPT;
            case Rule.Continue:
                return RuleResult.Action.CONTINUE;
            case Rule.Return:
                return RuleResult.Action.RETURN;
            default:
                throw new IllegalArgumentException("Invalid action " + flowAction);
        }
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

    public static org.midonet.cluster.data.Rule<?, ?> toData(Rule dto) {
        org.midonet.cluster.data.Rule<?, ?> data = null;
        if (Rule.RuleType.LITERAL.equals(dto.type)) {
            data = new LiteralRule(makeCondition(dto));
            data.setAction(toSimAction(dto.action));
        } else if (Rule.RuleType.MIRROR.equals(dto.type)) {
            MirrorRule mirrorRule = (MirrorRule) dto;
            data = toData(mirrorRule);
            data.setAction(toSimAction(dto.action));
        } else if (Rule.RuleType.JUMP.equals(dto.type)) {
            data = toData((JumpRule)dto);
            data.setAction(toSimAction(dto.action));
        } else if (Rule.RuleType.NAT.equals(dto.type)) {
            if (dto instanceof ForwardSnatRule) {
                data = toData((ForwardSnatRule) dto);
            } else if (dto instanceof ForwardDnatRule) {
                data = toData((ForwardDnatRule) dto);
            } else if (dto instanceof ReverseDnatRule) {
                data = toData((ReverseDnatRule)dto);
            } else if (dto instanceof ReverseSnatRule) {
                data = toData((ReverseSnatRule)dto);
            } else {
                throw new IllegalArgumentException("Unknown NAT rule");
            }
        } else if (Rule.RuleType.TRACE.equals(dto.type)) {
            TraceRule tr = (TraceRule)dto;
            data = new org.midonet.cluster.data.rules.TraceRule(tr.requestId,
                                                makeCondition(dto), tr.limit);
            data.setAction(toSimAction(dto.action));
        }

        if (data == null) {
            throw new IllegalArgumentException("Cannot translate dto rule");
        }

        data.setId(dto.id);
        data.setChainId(dto.chainId);
        data.setPosition(dto.position);
        data.setCondition(makeCondition(dto));
        data.setMeterName(dto.meterName);
        return data;
    }

    private static org.midonet.cluster.data.Rule<?, ?> toData(MirrorRule rule) {
        org.midonet.cluster.data.rules.MirrorRule mirrorRule =
                new org.midonet.cluster.data.rules.MirrorRule(
                        makeCondition(rule));
        mirrorRule.setPortId(rule.dstPortId);

        return mirrorRule;
    }

    private static org.midonet.cluster.data.Rule<?, ?> toData(JumpRule rule) {
        org.midonet.cluster.data.rules.JumpRule jRule =
            new org.midonet.cluster.data.rules.JumpRule(makeCondition(rule));
        jRule.setJumpToChainId(rule.jumpChainId);
        jRule.setJumpToChainName(rule.jumpChainName);
        return jRule;
    }

    private static org.midonet.cluster.data.Rule<?, ?> toData(
        ReverseSnatRule rule) {
        return org.midonet.cluster.data.rules.ReverseNatRule.snat(
            makeCondition(rule),
            getNatFlowAction(rule.flowAction)
        );
    }

    private static org.midonet.cluster.data.Rule<?, ?> toData(
        ReverseDnatRule rule) {
        return org.midonet.cluster.data.rules.ReverseNatRule.dnat(
            makeCondition(rule),
            getNatFlowAction(rule.flowAction)
        );
    }

    private static org.midonet.cluster.data.Rule<?, ?> toData(
        ForwardSnatRule rule) {
        return org.midonet.cluster.data.rules.ForwardNatRule.snat(
                makeCondition(rule),
                getNatFlowAction(rule.flowAction),
                makeTargetsForRule(rule));
    }

    private static org.midonet.cluster.data.Rule<?, ?> toData(
        ForwardDnatRule rule) {
        return org.midonet.cluster.data.rules.ForwardNatRule.dnat(
            makeCondition(rule),
            getNatFlowAction(rule.flowAction),
            makeTargetsForRule(rule));
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

    protected static Set<org.midonet.midolman.rules.NatTarget>
    makeTargetsForRule(ForwardNatRule natRule) {

        Set<org.midonet.midolman.rules.NatTarget> targets =
            new HashSet<>(natRule.natTargets.length);

        for (ForwardNatRule.NatTarget natTarget : natRule.natTargets) {
            org.midonet.midolman.rules.NatTarget t =
                new org.midonet.midolman.rules.NatTarget(
                    IPv4Addr.stringToInt(natTarget.addressFrom),
                    IPv4Addr.stringToInt(natTarget.addressTo),
                    natTarget.portFrom, natTarget.portTo);
            targets.add(t);
        }
        return targets;
    }

}
