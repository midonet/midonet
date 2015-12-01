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

import java.util.UUID;

import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Pattern;

import com.google.protobuf.Message;

import org.midonet.cluster.data.ZoomField;
import org.midonet.cluster.data.ZoomOneOf;

@ZoomOneOf(name = "transform_rule_data")
public class L2TransformRule extends Rule {

    @NotNull
    @Pattern(regexp = Rule.Accept + "|" + Rule.Redirect)
    public String flowAction;

    @ZoomField(name = "pop_vlan")
    public boolean popVlan;

    @ZoomField(name = "push_vlan")
    @Min(0)
    @Max(0xFFFF)
    public int pushVlan;

    @ZoomField(name = "target_port_id")
    public UUID targetPortId;

    @ZoomField(name = "ingress")
    public boolean ingress;

    @ZoomField(name = "fail_open")
    public boolean failOpen;

    public L2TransformRule() {
        super(RuleType.L2TRANSFORM, RuleAction.DROP);
    }

    @Override
    public final String getType() {
        return Rule.L2Transform;
    }

    public void afterFromProto(Message proto) {
        super.afterFromProto(proto);
        switch (action) {
            case ACCEPT: flowAction = Rule.Accept; break;
            case REDIRECT: flowAction = Rule.Redirect; break;
            default: throw new IllegalArgumentException("Invalid action");
        }
    }

    public void beforeToProto() {
        super.beforeToProto();
        switch (flowAction) {
            case Rule.Accept: action = RuleAction.ACCEPT; break;
            case Rule.Redirect: action = RuleAction.REDIRECT; break;
            default: throw new IllegalArgumentException("Invalid action");
        }
    }

}
