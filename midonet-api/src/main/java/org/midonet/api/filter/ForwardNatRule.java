/*
 * Copyright 2012 Midokura PTE LTD.
 */
package org.midonet.api.filter;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.midonet.packets.IPv4Addr;


/**
 * Forward NAT rule DTO
 */
public abstract class ForwardNatRule extends NatRule {

    protected NatTarget[] natTargets = {};

    public ForwardNatRule() {
        super();
    }

    public ForwardNatRule(
            org.midonet.cluster.data.rules.ForwardNatRule rule) {
        super(rule);
        this.natTargets = makeTargetsFromRule(rule);
    }

    public NatTarget[] getNatTargets() {
        return natTargets;
    }

    public void setNatTargets(NatTarget[] natTargets) {
        this.natTargets = natTargets;
    }

    protected static NatTarget[] makeTargetsFromRule(
            org.midonet.cluster.data.rules.ForwardNatRule natRule) {
        Set<org.midonet.midolman.rules.NatTarget> ruleTargets = natRule
                .getTargets();

        List<NatTarget> targets = new ArrayList<NatTarget>(ruleTargets.size());

        for (org.midonet.midolman.rules.NatTarget natTarget : ruleTargets) {
            NatTarget target = new NatTarget();

            target.addressFrom = natTarget.nwStart.toString();
            target.addressTo = natTarget.nwEnd.toString();

            target.portFrom = natTarget.tpStart;
            target.portTo = natTarget.tpEnd;

            targets.add(target);
        }

        return targets.toArray(new NatTarget[ruleTargets.size()]);
    }

    protected Set<org.midonet.midolman.rules.NatTarget> makeTargetsForRule() {
        Set<org.midonet.midolman.rules.NatTarget> targets =
                new HashSet<org.midonet.midolman.rules.NatTarget>(
                        natTargets.length);

        for (NatTarget natTarget : natTargets) {
            org.midonet.midolman.rules.NatTarget t =
                    new org.midonet.midolman.rules.NatTarget(
                            IPv4Addr.stringToInt(natTarget.addressFrom),
                            IPv4Addr.stringToInt(natTarget.addressTo),
                            (short) natTarget.portFrom,
                            (short) natTarget.portTo);
            targets.add(t);
        }
        return targets;
    }

    public static class NatTarget {
        public String addressFrom, addressTo;
        public int portFrom, portTo;

        public NatTarget() {
        }

        @Override
        public String toString() {
            return "NatTarget{" + "addressFrom='" + addressFrom + '\''
                    + ", addressTo='" + addressTo + '\'' + ", portFrom="
                    + portFrom + ", portTo=" + portTo + '}';
        }
    }
}
