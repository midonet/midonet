/*
 * Copyright 2012 Midokura PTE LTD.
 */
package org.midonet.api.filter;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import org.midonet.api.rest_api.BadRequestHttpException;
import org.midonet.api.validation.MessageProperty;
import org.midonet.midolman.rules.FragmentPolicy;
import org.midonet.packets.IPv4Addr;

import javax.validation.constraints.NotNull;
import javax.validation.constraints.Size;


/**
 * Forward NAT rule DTO
 */
public abstract class ForwardNatRule extends NatRule {

    @NotNull
    @Size(min = 1)
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
                new HashSet<>(natTargets.length);

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

    @Override
    public FragmentPolicy getAndValidateFragmentPolicy() {
        boolean unfragmentedOnly = !isFloatingIp() || hasL4Fields();
        if (getFragmentPolicy() == null) {
            return unfragmentedOnly ?
                    FragmentPolicy.UNFRAGMENTED : FragmentPolicy.ANY;
        }

        FragmentPolicy fp =
                FragmentPolicy.valueOf(getFragmentPolicy().toUpperCase());
        if (unfragmentedOnly && fp != FragmentPolicy.UNFRAGMENTED) {
            throw new BadRequestHttpException(MessageProperty.getMessage(
                    MessageProperty.FRAG_POLICY_INVALID_FOR_NAT_RULE));
        }

        return fp;
    }

    protected boolean isFloatingIp() {
        return natTargets != null && natTargets.length == 1 &&
                Objects.equals(natTargets[0].addressFrom,
                               natTargets[0].addressTo) &&
                natTargets[0].portFrom == 0 && natTargets[0].portTo == 0;
    }

    public static class NatTarget {
        @NotNull
        public String addressFrom;

        @NotNull
        public String addressTo;

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
