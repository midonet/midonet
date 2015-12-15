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

        public Topology.Rule.Action toProto() {
            return Topology.Rule.Action.valueOf(toString());
        }
    }

    public final Action action;

    public boolean matched = false;
    public final UUID jumpToChain;
    public final UUID redirectPort;
    public final boolean redirectIngress;

    public RuleResult(Action action) {
        this(action, null, null, false);
    }

    private RuleResult(Action action,
                       UUID jumpToChain,
                       UUID redirectPort,
                       boolean redirectIngress) {
        this.action = action;
        this.matched = false;
        this.jumpToChain = jumpToChain;
        this.redirectPort = redirectPort;
        this.redirectIngress = redirectIngress;
    }

    public boolean isDecisive() {
        return action.isDecisive();
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
        return action != res.action
            && Objects.equals(jumpToChain, res.jumpToChain)
            && redirectIngress != res.redirectIngress
            && Objects.equals(redirectPort, res.redirectPort);
    }

    @Override
    public String toString() {
        if (action == Action.JUMP)
            return action + "(jump=" + jumpToChain + ")";
        else if (action == Action.REDIRECT)
            return action + "(port=" + redirectPort
                + ", ingress=" + redirectIngress +")";
        else
            return action.toString();
    }

    public static RuleResult jumpResult(UUID jumpToChain) {
        return new RuleResult(Action.JUMP, jumpToChain, null, false);
    }

    public static RuleResult redirectResult(UUID redirectPort,
                                            boolean redirectIngress) {
        return new RuleResult(Action.REDIRECT, null, redirectPort,
                              redirectIngress);
    }
}
