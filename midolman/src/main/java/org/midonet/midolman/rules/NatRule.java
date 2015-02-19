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

package org.midonet.midolman.rules;

import java.util.UUID;

import org.midonet.cluster.data.ZoomClass;
import org.midonet.cluster.data.ZoomConvert;
import org.midonet.cluster.data.ZoomField;
import org.midonet.cluster.data.ZoomOneOf;
import org.midonet.cluster.models.Topology;
import org.midonet.midolman.rules.RuleResult.Action;

@ZoomClass(clazz = Topology.Rule.class, factory = NatRule.NatRuleFactory.class)
@ZoomOneOf(name = "nat_rule_data")
public abstract class NatRule extends Rule {
    @ZoomField(name = "dnat")
    public boolean dnat;

    public NatRule(Condition condition, Action action, boolean dnat) {
        super(condition, action);
        this.dnat = dnat;
        if (!action.equals(Action.ACCEPT) && !action.equals(Action.CONTINUE)
                && !action.equals(Action.RETURN))
            throw new IllegalArgumentException("A nat rule's action "
                    + "must be one of: ACCEPT, CONTINUE, or RETURN.");
    }

    // Default constructor for the Jackson deserialization.
    public NatRule() { super(); }

    public NatRule(Condition condition, Action action, UUID chainId,
            int position, boolean dnat) {
        super(condition, action, chainId, position);
        this.dnat = dnat;
        if (!action.equals(Action.ACCEPT) && !action.equals(Action.CONTINUE)
                && !action.equals(Action.RETURN))
            throw new IllegalArgumentException("A nat rule's action "
                    + "must be one of: ACCEPT, CONTINUE, or RETURN.");
    }

    @Override
    public int hashCode() {
        return super.hashCode() * 11 + (dnat ? 1231 : 1237);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other)
            return true;
        if (!(other instanceof NatRule))
            return false;
        if (!super.equals(other))
            return false;
        return dnat == ((NatRule) other).dnat;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder(super.toString());
        sb.append(", dnat=").append(dnat);
        return sb.toString();
    }

    public static class NatRuleFactory
        implements ZoomConvert.Factory<NatRule, Topology.Rule> {

        public Class<? extends NatRule> getType(Topology.Rule proto) {
            if (proto.getMatchForwardFlow())
                return ForwardNatRule.class;
            else if (proto.getMatchReturnFlow())
                return ReverseNatRule.class;
            else
                throw new ZoomConvert.ConvertException("Rule: " +
                    proto.getId() + " is neither a forward nor a reverse " +
                    "NAT rule");
        }
    }
}
