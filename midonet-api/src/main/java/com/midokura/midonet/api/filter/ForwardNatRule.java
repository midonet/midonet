/*
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midonet.api.filter;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.midokura.packets.Net;


/**
 * Forward NAT rule DTO
 */
public abstract class ForwardNatRule extends NatRule {

    protected NatTarget[] natTargets = {};

    public ForwardNatRule() {
        super();
    }

    public ForwardNatRule(
            com.midokura.midonet.cluster.data.rules.ForwardNatRule rule) {
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
            com.midokura.midonet.cluster.data.rules.ForwardNatRule natRule) {
        Set<com.midokura.midolman.rules.NatTarget> ruleTargets = natRule
                .getTargets();

        List<NatTarget> targets = new ArrayList<NatTarget>(ruleTargets.size());

        for (com.midokura.midolman.rules.NatTarget natTarget : ruleTargets) {
            NatTarget target = new NatTarget();

            target.addressFrom = Net
                    .convertIntAddressToString(natTarget.nwStart);
            target.addressTo = Net.convertIntAddressToString(natTarget.nwEnd);

            target.portFrom = natTarget.tpStart;
            target.portTo = natTarget.tpEnd;

            targets.add(target);
        }

        return targets.toArray(new NatTarget[ruleTargets.size()]);
    }

    protected Set<com.midokura.midolman.rules.NatTarget> makeTargetsForRule() {
        Set<com.midokura.midolman.rules.NatTarget> targets =
                new HashSet<com.midokura.midolman.rules.NatTarget>(
                        natTargets.length);

        for (NatTarget natTarget : natTargets) {
            com.midokura.midolman.rules.NatTarget t =
                    new com.midokura.midolman.rules.NatTarget(
                            Net.convertStringAddressToInt(
                                    natTarget.addressFrom),
                            Net.convertStringAddressToInt(
                                    natTarget.addressTo),
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
