/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */
package org.midonet.midolman.rules;

import com.google.common.base.Function;
import com.google.common.base.Objects;
import org.midonet.packets.IPAddr;
import org.midonet.packets.IPv4Addr;
import org.midonet.packets.IPv4Subnet;

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

        public SnatRuleMatcher(IPAddr nwStart, IPAddr nwEnd) {
            this(new NatTarget(nwStart, nwEnd));
        }

        public SnatRuleMatcher(IPAddr addr) {
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

    public static class DnatRuleMatcher extends RuleMatcher {

        private final NatTarget target;

        public DnatRuleMatcher(NatTarget target) {
            this.target = target;
        }

        public DnatRuleMatcher(IPAddr nwStart, IPAddr nwEnd) {
            this(new NatTarget(nwStart, nwEnd));
        }

        public DnatRuleMatcher(IPAddr addr) {
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