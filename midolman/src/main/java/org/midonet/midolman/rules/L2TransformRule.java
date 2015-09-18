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

package org.midonet.midolman.rules;

import java.util.Objects;

import org.midonet.cluster.data.ZoomClass;
import org.midonet.cluster.data.ZoomConvert;
import org.midonet.cluster.data.ZoomField;
import org.midonet.cluster.data.ZoomOneOf;
import org.midonet.cluster.models.Topology;
import org.midonet.midolman.simulation.PacketContext;

@ZoomClass(clazz = Topology.Rule.class, factory = L2TransformRule.L2TransformRuleFactory.class)
@ZoomOneOf(name = "transform_rule_data")
public class L2TransformRule extends Rule {

    private static final long serialVersionUID = -7212783590950701193L;

    @ZoomField(name = "pop_vlan")
    public boolean popVlan;
    @ZoomField(name = "push_vlan")
    public short pushVlan;

    // Default constructor for the Jackson deserialization.
    // This constructor is also needed by ZoomConvert.
    public L2TransformRule() {
        super();
    }

    @Override
    public boolean apply(PacketContext pktCtx) {
        boolean transformed = false;
        if (popVlan) {
            short vlan = pktCtx.wcmatch().getVlanIds().remove(0);
            pktCtx.jlog().debug("popping vlan {}", vlan);
            transformed = true;
        }
        if (pushVlan != 0) {
            pktCtx.wcmatch().addVlanId((short)pushVlan);
            pktCtx.jlog().debug("pushing vlan {}", pushVlan);
            transformed = true;
        }
        return transformed;
    }

    @Override
    public int hashCode() {
        return Objects.hash(popVlan, pushVlan);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof RedirectRule)) return false;
        if (!super.equals(other)) return false;

        L2TransformRule res = (L2TransformRule)other;
        return Objects.equals(popVlan, res.popVlan)
            && Objects.equals(pushVlan, res.pushVlan);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("L2TransformRule [");
        sb.append(super.toString());
        sb.append(", popVlan=").append(popVlan);
        sb.append(", pushVlan=").append(pushVlan);
        sb.append("]");
        return sb.toString();
    }

    public static class L2TransformRuleFactory
        implements ZoomConvert.Factory<L2TransformRule, Topology.Rule> {

        public Class<? extends L2TransformRule> getType(Topology.Rule proto) {
            if (proto.getAction() == Topology.Rule.Action.REDIRECT) {
                return RedirectRule.class;
            } else {
                return L2TransformRule.class;
            }
        }
    }

}
