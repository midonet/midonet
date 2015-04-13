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

package org.midonet.brain.services.rest_api.models;

import javax.validation.constraints.NotNull;
import javax.xml.bind.annotation.XmlTransient;

import com.google.protobuf.MessageOrBuilder;

import org.midonet.cluster.data.ZoomClass;
import org.midonet.cluster.data.ZoomConvert;
import org.midonet.cluster.data.ZoomField;
import org.midonet.cluster.data.ZoomOneOf;
import org.midonet.cluster.models.Topology;

@ZoomClass(clazz = Topology.Rule.class, factory = NatRule.NatRuleFactory.class)
@ZoomOneOf(name = "nat_rule_data")
public abstract class NatRule extends Rule {

    @NotNull
    public String flowAction;
    @XmlTransient
    @ZoomField(name = "dnat")
    public boolean dnat;
    @XmlTransient
    @ZoomField(name = "reverse")
    public boolean reverse;

    public NatRule() {
        super(RuleType.NAT, RuleAction.DROP);
    }

    public static class NatRuleFactory
        implements ZoomConvert.Factory<NatRule, Topology.Rule> {

        public Class<? extends NatRule> getType(Topology.Rule proto) {
            if (proto.getNatRuleData().getReverse()) {
                if (proto.getNatRuleData().getDnat())
                    return ReverseDnatRule.class;
                else
                    return ReverseSnatRule.class;
            } else {
                if (proto.getNatRuleData().getDnat())
                    return ForwardDnatRule.class;
                else
                    return ForwardSnatRule.class;
            }
        }
    }

    @Override
    public void afterFromProto(MessageOrBuilder proto) {
        switch (action) {
            case ACCEPT: flowAction = Rule.Accept; break;
            case CONTINUE: flowAction = Rule.Continue; break;
            case RETURN: flowAction = Rule.Return; break;
            default: throw new IllegalArgumentException("Invalid action");
        }
    }

    @Override
    public void beforeToProto() {
        if (flowAction.equals(Rule.Accept)) action = RuleAction.ACCEPT;
        else if (flowAction.equals(Rule.Continue)) action = RuleAction.CONTINUE;
        else if (flowAction.equals(Rule.Return)) action = RuleAction.RETURN;
        else throw new IllegalArgumentException("Invalid action");
    }

}
