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

import java.util.Objects;
import java.util.UUID;

import org.midonet.cluster.data.ZoomEnum;
import org.midonet.cluster.data.ZoomEnumValue;
import org.midonet.cluster.models.Topology;

public class RuleResult {

    @ZoomEnum(clazz = Topology.Rule.Action.class)
    public enum Action {
        @ZoomEnumValue(value = "ACCEPT")
        ACCEPT(true),
        @ZoomEnumValue(value = "CONTINUE")
        CONTINUE(false),
        @ZoomEnumValue(value = "DROP")
        DROP(true),
        @ZoomEnumValue(value = "JUMP")
        JUMP(false),
        @ZoomEnumValue(value = "REJECT")
        REJECT(true),
        @ZoomEnumValue(value = "RETURN")
        RETURN(false),
        @ZoomEnumValue(value = "REDIRECT")
        REDIRECT(true);

        private final boolean decisive;

        private Action(boolean decisive) { this.decisive = decisive; }

        /**
         * A decisive action, such as ACCEPT or REJECT, is one which
         * determines the result of a chain application and thus allows
         * us to skip processing of any further rules.
         */
        public boolean isDecisive() { return decisive; }
    }

    public Action action;
    public UUID jumpToChain;
    public UUID redirectPort;
    public boolean redirectIngress;

    public RuleResult(Action action, UUID jumpToChain) {
        this.action = action;
        this.jumpToChain = jumpToChain;
    }

    public RuleResult(UUID redirectPort, boolean redirectIngress) {
        this.action = Action.REDIRECT;
        this.redirectPort = redirectPort;
        this.redirectIngress = redirectIngress;
    }

    @Override
    public int hashCode() {
        return Objects.hash(action, jumpToChain, redirectPort);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof RuleResult)) return false;

        RuleResult res = (RuleResult)other;
        if (action != res.action) return false;
        if (redirectIngress != res.redirectIngress) return false;
        if (!Objects.equals(jumpToChain, res.jumpToChain)) return false;
        if (!Objects.equals(redirectPort, res.redirectPort)) return false;
        return true;
    }

    @Override
    public String toString() {
        if (action == Action.JUMP)
            return action + "(jump=" + jumpToChain + ")";
        else
            return action.toString();
    }
}
