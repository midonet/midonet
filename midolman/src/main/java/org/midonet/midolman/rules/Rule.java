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

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.google.protobuf.Message;

import org.midonet.cluster.data.ZoomClass;
import org.midonet.cluster.data.ZoomConvert;
import org.midonet.cluster.data.ZoomField;
import org.midonet.cluster.models.Topology;
import org.midonet.cluster.util.UUIDUtil;
import org.midonet.midolman.rules.RuleResult.Action;
import org.midonet.midolman.simulation.Chain;
import org.midonet.midolman.simulation.PacketContext;
import org.midonet.nsdb.BaseConfig;
import org.midonet.sdn.flows.FlowTagger;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY,
    property = "type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = LiteralRule.class, name = "Literal"),
    @JsonSubTypes.Type(value = TraceRule.class, name = "Trace"),
    @JsonSubTypes.Type(value = JumpRule.class, name = "Jump"),
    @JsonSubTypes.Type(value = DynamicForwardNatRule.class, name = "DynamicForwardNat"),
    @JsonSubTypes.Type(value = StaticForwardNatRule.class, name = "StaticForwardNat"),
    @JsonSubTypes.Type(value = ReverseNatRule.class, name = "ReverseNat")
})

@ZoomClass(clazz = Topology.Rule.class, factory = Rule.RuleFactory.class)
public abstract class Rule extends BaseConfig {

    @ZoomField(name = "condition")
    protected Condition condition;

    @ZoomField(name = "action")
    public Action action;
    @ZoomField(name = "chain_id")
    public UUID chainId;

    @JsonIgnore
    public FlowTagger.UserTag meter;
    private Map<String, String> properties = new HashMap<>();
    private Map<String, String> metadata = new HashMap<>();

    protected RuleResult result;

    public Rule(Condition condition, Action action) {
        this(condition, action, null);
    }

    public Rule(Condition condition, Action action, UUID chainId) {
        this.condition = condition;
        this.action = action;
        this.chainId = chainId;
        result = new RuleResult(action);
    }

    public void afterFromProto(Message proto) {
        // Validate the rule message.
        Topology.Rule rule = (Topology.Rule)proto;
        UUID id = UUIDUtil.fromProto(rule.getId());

        if (!rule.hasNat64RuleData() && !rule.hasAction()) {
            throw new ZoomConvert.ConvertException(
                "Rule " + id + " has no action set (" + rule + ")");
        }

        // When the rule has no condition set, the condition matches everything.
        if (!rule.hasCondition()) {
            this.condition = Condition.TRUE;
        }

        switch (rule.getType()) {
            case JUMP_RULE:
                if (!rule.hasJumpRuleData()) {
                    throw new ZoomConvert.ConvertException(
                        "Rule " + id + " is a JUMP rule but does not have its "
                        + "JUMP data set (" + rule + ")");
                }
                if (rule.getAction() != Topology.Rule.Action.JUMP)
                    throw new ZoomConvert.ConvertException(
                        "Rule " + id + " is a JUMP rule but does not have its "
                        + "action set to JUMP (" + rule + ")");
                break;

            case NAT_RULE:
                if (!rule.hasNatRuleData()) {
                    throw new ZoomConvert.ConvertException(
                        "Rule " + id + " is a NAT rule but does not have its "
                        + "NAT data set (" + rule + ")");
                }
                if (!rule.getNatRuleData().getReverse() &&
                    rule.getNatRuleData().getNatTargetsCount() == 0) {
                    throw new ZoomConvert.ConvertException(
                        "Rule " + id + " is a forward NAT rule but has no "
                        + "targets set (" + rule + ")");
                }
                break;

            case NAT64_RULE:
                if (!rule.hasNat64RuleData()) {
                    throw new ZoomConvert.ConvertException(
                        "Rule " + id + " is a NAT64 rule but it does not have "
                        + "its NAT64 data set (" + rule + ")");
                }
                if (!rule.getNat64RuleData().hasPortAddress()) {
                    throw new ZoomConvert.ConvertException(
                        "Rule " + id + " is a NAT64 rule but it does not have "
                        + "its port address set (" + rule + ")");
                }
                if (!rule.getNat64RuleData().hasNatPool()) {
                    throw new ZoomConvert.ConvertException(
                        "Rule " + id + " is a NAT64 rule but it does not have "
                        + "its NAT pool set (" + rule + ")");
                }
                break;

            case TRACE_RULE:
                if (rule.getAction() != Topology.Rule.Action.CONTINUE)
                    throw new ZoomConvert.ConvertException(
                        "Rule " + id + " is a TRACE rule but its action is not "
                        + " set to CONTINUE (" + rule + ")");
                break;
        }

        result = new RuleResult(action);
    }

    // WARNING!
    // Conversion of an object of a subclass of Rule is not supported.
    // The reason is that the type field of the protocol buffer determines the
    // subclass of Rule the pojo will be an instance of. As a consequence,
    // calling toProto on an object of a subclass of Rule will not set the type
    // field in the proto. We thus throw a ConvertException when the conversion
    // is attempted.
    public void beforeToProto() {
        throw new ZoomConvert.ConvertException("Conversion of an object of " +
            "class: " + getClass() + " to a protocol buffer is not " +
            "supported");
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

    public RuleResult process(PacketContext pktCtx) {
        if (condition.matches(pktCtx)) {
            pktCtx.jlog().debug(
                    "Condition matched on device {} chain {} with action {} and condition {}",
                    pktCtx.currentDevice(), chainId, action, condition);
            pktCtx.recordMatchedRule(id, true);
            if (meter != null)
                pktCtx.addFlowTag(meter);
            if (apply(pktCtx)) {
                pktCtx.recordAppliedRule(id, true);
                return result;
            }
        } else {
            pktCtx.recordMatchedRule(id, false);
        }
        pktCtx.recordAppliedRule(id, false);
        return Chain.CONTINUE();
    }

    public Condition getCondition() {
        return condition;
    }

    protected abstract boolean apply(PacketContext pktCtx);

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
        public Class<? extends Rule> getType(Topology.Rule proto) {
            switch (proto.getType()) {
                case JUMP_RULE: return JumpRule.class;
                case LITERAL_RULE: return LiteralRule.class;
                case TRACE_RULE: return TraceRule.class;
                case NAT_RULE: return NatRule.class;
                case NAT64_RULE: return Nat64Rule.class;
                case L2TRANSFORM_RULE: return L2TransformRule.class;
                default:
                    throw new ZoomConvert.ConvertException(
                        "Unknown rule type: " + proto.getType());
            }
        }
    }
}
