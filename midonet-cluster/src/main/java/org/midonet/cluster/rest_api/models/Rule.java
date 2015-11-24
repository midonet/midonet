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

import java.net.URI;
import java.util.UUID;

import javax.validation.constraints.Min;
import javax.validation.constraints.NotNull;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.google.common.base.MoreObjects;
import com.google.common.base.MoreObjects.ToStringHelper;
import com.google.protobuf.Message;

import org.midonet.cluster.data.ZoomClass;
import org.midonet.cluster.data.ZoomConvert;
import org.midonet.cluster.data.ZoomEnum;
import org.midonet.cluster.data.ZoomEnumValue;
import org.midonet.cluster.data.ZoomField;
import org.midonet.cluster.models.Commons;
import org.midonet.cluster.models.Topology;
import org.midonet.cluster.rest_api.ResourceUris;
import org.midonet.cluster.util.UUIDUtil;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY,
    property = "type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = AcceptRule.class, name = Rule.Accept),
    @JsonSubTypes.Type(value = DropRule.class, name = Rule.Drop),
    @JsonSubTypes.Type(value = ForwardDnatRule.class, name = Rule.DNAT),
    @JsonSubTypes.Type(value = ForwardSnatRule.class, name = Rule.SNAT),
    @JsonSubTypes.Type(value = JumpRule.class, name = Rule.Jump),
    @JsonSubTypes.Type(value = L2TransformRule.class, name = Rule.L2Transform),
    @JsonSubTypes.Type(value = RejectRule.class, name = Rule.Reject),
    @JsonSubTypes.Type(value = ReturnRule.class, name = Rule.Return),
    @JsonSubTypes.Type(value = ReverseDnatRule.class, name = Rule.RevDNAT),
    @JsonSubTypes.Type(value = ReverseSnatRule.class, name = Rule.RevSNAT),
    @JsonSubTypes.Type(value = TraceRule.class, name = Rule.Trace)})
@ZoomClass(clazz = Topology.Rule.class, factory = Rule.Factory.class, skipSuper = true)
public abstract class Rule extends Condition {

    public static class Factory implements ZoomConvert.Factory<Rule, Topology.Rule> {
        public Class<? extends Rule> getType(Topology.Rule proto) {
            switch (proto.getType()) {
                case JUMP_RULE: return JumpRule.class;
                case LITERAL_RULE:
                    switch (proto.getAction()) {
                        case ACCEPT: return AcceptRule.class;
                        case DROP: return DropRule.class;
                        case REJECT: return RejectRule.class;
                        case RETURN: return ReturnRule.class;
                    }
                case TRACE_RULE: return TraceRule.class;
                case NAT_RULE: return NatRule.class;
                case L2TRANSFORM_RULE: return L2TransformRule.class;
                default: throw new ZoomConvert.ConvertException(
                    "Unknown rule type: " + proto.getType());
            }
        }
    }

    public static final String Accept = "accept";
    public static final String Continue = "continue";
    public static final String DNAT = "dnat";
    public static final String Drop = "drop";
    public static final String Jump = "jump";
    public static final String L2Transform = "l2_transform";
    public static final String Redirect = "redirect";
    public static final String Reject = "reject";
    public static final String Return = "return";
    public static final String RevDNAT = "rev_dnat";
    public static final String RevSNAT = "rev_snat";
    public static final String SNAT = "snat";
    public static final String Trace = "trace";

    @ZoomEnum(clazz = Topology.Rule.Type.class)
    public enum RuleType {
        @ZoomEnumValue(value = "LITERAL_RULE") LITERAL,
        @ZoomEnumValue(value = "NAT_RULE") NAT,
        @ZoomEnumValue(value = "JUMP_RULE") JUMP,
        @ZoomEnumValue(value = "TRACE_RULE") TRACE,
        @ZoomEnumValue(value = "L2TRANSFORM_RULE") L2TRANSFORM
    }

    @ZoomEnum(clazz = Topology.Rule.Action.class)
    public enum RuleAction {
        @ZoomEnumValue(value = "ACCEPT") ACCEPT,
        @ZoomEnumValue(value = "CONTINUE") CONTINUE,
        @ZoomEnumValue(value = "DROP") DROP,
        @ZoomEnumValue(value = "JUMP") JUMP,
        @ZoomEnumValue(value = "REJECT") REJECT,
        @ZoomEnumValue(value = "RETURN") RETURN,
        @ZoomEnumValue(value = "REDIRECT") REDIRECT
    }

    @ZoomField(name = "id", converter = UUIDUtil.Converter.class)
    public UUID id;

    @ZoomField(name = "chain_id", converter = UUIDUtil.Converter.class)
    public UUID chainId;

    @JsonIgnore
    @ZoomField(name = "type")
    @NotNull
    public final RuleType type;

    @JsonIgnore
    @ZoomField(name = "action")
    public RuleAction action;

    // TODO: Add support in ZOOM
    public String meterName;

    @Min(1)
    public int position = 1;

    public Rule(RuleType type, RuleAction action) {
        this.type = type;
        this.action = action;
    }

    @NotNull
    public abstract String getType();

    @Override
    public URI getUri() {
        return absoluteUri(ResourceUris.RULES(), id);
    }

    @JsonIgnore
    public void create() {
        if (null == id) {
            id = UUID.randomUUID();
        }
        chainId = null;
        if (0 == position) {
            position = 1;
        }
    }

    @Override
    public void afterFromProto(Message proto) {
        super.afterFromProto(proto);

        if (proto instanceof Topology.Rule) {
            Topology.Rule rule = (Topology.Rule)proto;
            if (rule.hasCondition()) {
                Condition c = ZoomConvert.fromProto(rule.getCondition(),
                                                    Condition.class);
                this.copyFrom(c);
            }
        } else {
            throw new ZoomConvert.ConvertException("Message should be a Rule");
        }
    }

    @Override
    public void afterToProto(Message.Builder builder) {
        super.afterToProto(builder);
        if (builder instanceof Topology.Rule.Builder) {
            Topology.Rule.Builder ruleBuilder = (Topology.Rule.Builder)builder;
            Condition c = new Condition().copyFrom(this);
            ruleBuilder.setCondition(
                    ZoomConvert.toProto(c, Commons.Condition.class));
        } else {
            throw new ZoomConvert.ConvertException("Wrong builder type");
        }
    }

    @Override
    public String toString() {
        ToStringHelper tsh =
            MoreObjects.toStringHelper(this).omitNullValues()
                .add("id", id)
                .add("chainId", chainId)
                .add("type", type)
                .add("action", action)
                .add("meterName", meterName)
                .add("position", position);
        addConditionToStringHelper(tsh);
        return tsh.toString();
    }
}
