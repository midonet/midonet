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

package org.midonet.cluster.rest_api.models;

import javax.validation.constraints.NotNull;
import javax.validation.constraints.Pattern;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.google.protobuf.Message;

import org.midonet.cluster.data.ZoomClass;
import org.midonet.cluster.data.ZoomConvert;
import org.midonet.cluster.data.ZoomField;
import org.midonet.cluster.data.ZoomOneOf;
import org.midonet.cluster.models.Topology;

@ZoomClass(clazz = Topology.Rule.class, factory = NatRule.NatRuleFactory.class)
@ZoomOneOf(name = "nat_rule_data")
public abstract class NatRule extends Rule {

    @NotNull
    @Pattern(regexp = Rule.Accept + "|" + Rule.Continue + "|" + Rule.Return)
    public String flowAction;
    @JsonIgnore
    @ZoomField(name = "dnat")
    private boolean dnat;
    @JsonIgnore
    @ZoomField(name = "reverse")
    private boolean reverse;

    NatRule(boolean reverse, boolean dnat) {
        super(RuleType.NAT, RuleAction.DROP);
        this.reverse = reverse;
        this.dnat = dnat;
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

    @JsonIgnore
    @Override
    public void afterFromProto(Message proto) {
        super.afterFromProto(proto);
        switch (action) {
            case ACCEPT: flowAction = Rule.Accept; break;
            case CONTINUE: flowAction = Rule.Continue; break;
            case RETURN: flowAction = Rule.Return; break;
            default: throw new IllegalArgumentException("Invalid action");
        }
    }

    @JsonIgnore
    @Override
    public void beforeToProto() {
        super.beforeToProto();
        switch (flowAction) {
            case Rule.Accept: action = RuleAction.ACCEPT; break;
            case Rule.Continue: action = RuleAction.CONTINUE; break;
            case Rule.Return: action = RuleAction.RETURN; break;
            default: throw new IllegalArgumentException("Invalid action");
        }
    }

}
