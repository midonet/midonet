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

import com.google.common.base.Function;
import com.google.common.base.Objects;
import org.midonet.packets.IPv4Addr;
import org.midonet.packets.IPv4Subnet;

import java.util.UUID;

/**
 * Classes used to match on certain types of rules.
 */
public abstract class RuleMatcher implements Function<Rule, Boolean> {

    abstract public Boolean apply(Rule rule);

    public static class SnatRuleMatcher extends RuleMatcher {

        private final NatTarget target;

        public SnatRuleMatcher(NatTarget target) {
            this.target = target;
        }

        public SnatRuleMatcher(IPv4Addr addr) {
            this(new NatTarget(addr, 0, 0));
        }

        @Override
        public Boolean apply(Rule rule) {
            if (!rule.getClass().equals(ForwardNatRule.class))
                return false;

            ForwardNatRule r = (ForwardNatRule) rule;
            return !r.dnat && r.getNatTargets().contains(target);
        }
    }

    public static class DefaultDropRuleMatcher extends RuleMatcher {
        private final IPv4Addr addr;

        public DefaultDropRuleMatcher(IPv4Addr addr) {
            this.addr = addr;
        }

        @Override
        public Boolean apply(Rule rule) {
            if (!rule.getClass().equals(LiteralRule.class))
                return false;
            LiteralRule r = (LiteralRule) rule;
            Rule dropRule = new RuleBuilder(UUID.randomUUID())
                .notICMP()
                .hasDestIp(addr)
                .drop();
            Condition c = dropRule.getCondition();
            return r.action == dropRule.action &&
                   r.getCondition().nwProtoInv == c.nwProtoInv &&
                   Objects.equal(r.getCondition().nwProto, c.nwProto) &&
                   Objects.equal(r.getCondition().nwDstIp, c.nwDstIp);
        }
    }

    public static class DropFragmentRuleMatcher extends RuleMatcher {
        private final UUID portId;

        public DropFragmentRuleMatcher(UUID portId) {
            this.portId = portId;
        }

        @Override
        public Boolean apply(Rule rule) {
            if (!rule.getClass().equals(LiteralRule.class))
                return false;
            LiteralRule r = (LiteralRule) rule;
            return r.action == RuleResult.Action.DROP &&
                   r.getCondition().fragmentPolicy == FragmentPolicy.ANY &&
                   r.getCondition().outPortIds != null &&
                   r.getCondition().outPortIds.contains(portId);
        }
    }

    public static class DnatRuleMatcher extends RuleMatcher {

        private final NatTarget target;

        public DnatRuleMatcher(NatTarget target) {
            this.target = target;
        }

        public DnatRuleMatcher(IPv4Addr addr) {
            this(new NatTarget(addr, 0, 0));
        }

        @Override
        public Boolean apply(Rule rule) {
            if (!rule.getClass().equals(ForwardNatRule.class))
                return false;

            ForwardNatRule r = (ForwardNatRule) rule;
            return r.dnat && r.getNatTargets().contains(target);
        }
    }

    public static class ReverseSnatRuleMatcher extends RuleMatcher {

        private final IPv4Addr addr;

        public ReverseSnatRuleMatcher(IPv4Addr addr) {
            this.addr = addr;
        }

        @Override
        public Boolean apply(Rule rule) {
            if (!rule.getClass().equals(ReverseNatRule.class))
                return false;

            ReverseNatRule r = (ReverseNatRule) rule;
            Condition c = r.getCondition();
            return !r.dnat && Objects.equal(c.nwDstIp,
                    new IPv4Subnet(addr, 32));
        }
    }
}