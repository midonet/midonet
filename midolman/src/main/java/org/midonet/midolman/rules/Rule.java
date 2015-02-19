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

import java.util.*;

import com.google.protobuf.MessageOrBuilder;

import org.codehaus.jackson.annotate.JsonIgnore;
import org.codehaus.jackson.annotate.JsonProperty;
import org.codehaus.jackson.annotate.JsonSubTypes;
import org.codehaus.jackson.annotate.JsonTypeInfo;

import org.midonet.cluster.data.ZoomClass;
import org.midonet.cluster.data.ZoomConvert;
import org.midonet.cluster.data.ZoomEnum;
import org.midonet.cluster.data.ZoomField;
import org.midonet.cluster.data.ZoomObject;
import org.midonet.cluster.models.Topology;
import org.midonet.cluster.util.UUIDUtil;
import org.midonet.midolman.topology.VirtualTopology;
import org.midonet.sdn.flows.FlowTagger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.midonet.midolman.rules.RuleResult.Action;
import org.midonet.midolman.simulation.PacketContext;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY,
    property = "type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = LiteralRule.class, name = "Literal"),
    @JsonSubTypes.Type(value = TraceRule.class, name = "Trace"),
    @JsonSubTypes.Type(value = JumpRule.class, name = "Jump"),
    @JsonSubTypes.Type(value = ForwardNatRule.class, name = "ForwardNat"),
    @JsonSubTypes.Type(value = ReverseNatRule.class, name = "ReverseNat")
})

@ZoomClass(clazz = Topology.Rule.class, factory = Rule.RuleFactory.class)
public abstract class Rule extends ZoomObject {
    private final static Logger log = LoggerFactory.getLogger(Rule.class);

    protected Condition condition;

    @ZoomField(name = "action")
    public Action action;
    @ZoomField(name = "chain_id", converter = UUIDUtil.Converter.class)
    public UUID chainId;
    @JsonIgnore
    public FlowTagger.UserTag meter;
    private Map<String, String> properties = new HashMap<String, String>();

    public Rule(Condition condition, Action action) {
        this(condition, action, null);
    }

    public Rule(Condition condition, Action action, UUID chainId) {
        this.condition = condition;
        this.action = action;
        this.chainId = chainId;
    }

    public void afterFromProto(MessageOrBuilder proto) {
        condition = ZoomConvert.fromProto(proto, Condition.class);
        validateProto((Topology.Rule) proto);
    }

    private void validateProto(Topology.Rule protoRule) {
        if (!protoRule.hasAction())
            throw new IllegalArgumentException("Rule " + protoRule.getId() +
                                               " has no action set");

        switch (protoRule.getType()) {
            case JUMP_TYPE:
                if (protoRule.getAction() != Topology.Rule.Action.JUMP ||
                    !protoRule.hasJumpRuleData())
                    throw new ZoomConvert.ConvertException(
                        "Rule: " + protoRule.getId() + " is a JUMP rule but " +
                        "does not have its action set to JUMP or is missing " +
                        " its associated jump data");
                break;
            case NAT_TYPE:
                if (!protoRule.hasNatRuleData() || !protoRule.getNatRuleData()
                                                       .hasDnat())
                    throw new ZoomConvert.ConvertException(
                        "NAT rule: " + protoRule.getId() + " must have its " +
                        "boolean dnat set");

                if (protoRule.getMatchForwardFlow() &&
                    protoRule.getNatRuleData().getNatTargetsCount() == 0)
                        throw new ZoomConvert.ConvertException("Rule: " +
                            protoRule.getId() + " is a forward NAT rule but " +
                            "has no targets set");
                break;

            case TRACE_TYPE:
                if (protoRule.getAction() != Topology.Rule.Action.CONTINUE)
                    throw new IllegalArgumentException(
                        "Trace rule: " + protoRule.getId() + " must have its " +
                        " action set to CONTINUE");
                break;
        }
    }

    @JsonProperty
    public void setMeterName(String meterName) {
        meter = FlowTagger.tagForUserMeter(meterName);
    }

    @JsonProperty
    public String getMeterName() {
        return meter != null ? meter.name() : null;
    }

    // Default constructor for the Jackson deserialization.
    // This constructor is also used by ZoomConvert.
    public Rule() {
        super();
    }

    // Setter for Jackson serialization
    @SuppressWarnings("unused")
    private void setCondition(Condition cond) {
        this.condition = cond;
    }

    /**
     * If the packet specified by res.pmatch matches this rule's condition,
     * apply the rule.
     *
     * @param pktCtx       the PacketContext for the packet being processed
     * @param res          contains a match of the packet after all
     *                     transformations preceding this rule. This may be
     *                     modified.
     * @param isPortFilter whether the rule is being processed in a port filter
     *                     context
     */
    public void process(PacketContext pktCtx, RuleResult res, UUID ownerId,
                        boolean isPortFilter) {
        if (condition.matches(pktCtx, isPortFilter)) {
            pktCtx.jlog().debug(
                    "Condition matched on device {} chain {} with action {}",
                    ownerId, chainId, action);

            if (meter != null)
                pktCtx.addFlowTag(meter);
            apply(pktCtx, res, ownerId);
        }
    }

    public Condition getCondition() {
        return condition;
    }

    /**
     * Apply this rule to the packet specified by res.pmatch.
     *
     * @param pktCtx     the PacketContext for the packet being processed.
     * @param res        contains a match of the packet after all
     *                   transformations preceding this rule. This may be
     *                   modified.
     */
    protected abstract void apply(PacketContext pktCtx, RuleResult res,
                                  UUID ownerId);

    public Map<String, String> getProperties() {
        return properties;
    }

    public void setProperties(Map<String, String> properties) {
        this.properties = properties;
    }

    @Override
    public int hashCode() {
        int hash = condition.hashCode();
        if (null != action)
            hash = hash * 23 + action.hashCode();
        if (null != meter)
            hash = hash * 23 + meter.hashCode();
        return hash;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other)
            return true;
        if (!(other instanceof Rule))
            return false;
        Rule r = (Rule) other;
        if (!condition.equals(r.condition))
            return false;
        if (meter == null && r.meter != null)
            return false;
        if (meter != null && !meter.equals(r.meter))
            return false;
        if (null == action || null == r.action) {
            return action == r.action;
        } else {
            return action.equals(r.action);
        }
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("Rule[");
        sb.append("condition=").append(condition);
        sb.append(", action=").append(action);
        sb.append(", chainId=").append(chainId);
        if (meter != null)
            sb.append(", meter=").append(meter);
        sb.append("]");
        return sb.toString();
    }

    public static class RuleFactory implements ZoomConvert.Factory<Rule, Topology.Rule> {
        public java.lang.Class<? extends Rule> getType(Topology.Rule proto) {
            switch (proto.getType()) {
                case JUMP_TYPE: return JumpRule.class;
                case LITERAL_TYPE: return LiteralRule.class;
                case TRACE_TYPE: return TraceRule.class;
                case NAT_TYPE:
                    if (proto.getMatchForwardFlow())
                        return ForwardNatRule.class;
                    else if (proto.getMatchReturnFlow())
                        return ReverseNatRule.class;
                    else
                        throw new ZoomConvert.ConvertException("Rule: " +
                            proto.getId() + " is neither a forward nor a " +
                            "reverse  NAT rule");
                default:
                    throw new ZoomConvert.ConvertException("Unknown rule " +
                        "type: " + proto.getType());
            }
        }
    }
}
