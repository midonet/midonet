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

import javax.validation.constraints.NotNull;

import org.midonet.cluster.data.ZoomField;
import org.midonet.cluster.data.ZoomOneOf;

@ZoomOneOf(name = "jump_rule_data")
public class JumpRule extends Rule {

    @ZoomField(name = "jump_chain_name")
    public String jumpChainName;
    @NotNull
    @ZoomField(name = "jump_chain_id")
    public UUID jumpChainId;

    public JumpRule() {
        super(RuleType.JUMP, RuleAction.JUMP);
    }

    public JumpRule(UUID jumpChainId, String jumpChainName) {
        super(RuleType.JUMP, RuleAction.JUMP);
        this.jumpChainId = jumpChainId;
        this.jumpChainName = jumpChainName;
    }

    @Override
    public final String getType() {
        return Rule.Jump;
    }

}
