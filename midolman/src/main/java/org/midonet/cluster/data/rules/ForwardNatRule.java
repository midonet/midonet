/*
 * Copyright 2012 Midokura PTE LTD.
 */
package org.midonet.cluster.data.rules;

import org.midonet.midolman.rules.Condition;
import org.midonet.midolman.rules.NatTarget;
import org.midonet.midolman.rules.RuleResult;

import java.util.Set;
import java.util.UUID;

/**
 * Basic abstraction for a forward NAT rule
 */
public class ForwardNatRule
        extends NatRule<ForwardNatRule.Data, ForwardNatRule> {

    public ForwardNatRule(Condition condition, RuleResult.Action action,
                          Set<NatTarget> targets, boolean isDnat) {
        this(null, condition, action, targets, isDnat, new Data());
    }

    public ForwardNatRule(UUID uuid, Condition condition,
                          RuleResult.Action action, Set<NatTarget> targets,
                          boolean isDnat, ForwardNatRule.Data ruleData){
        super(uuid, condition, action, isDnat, ruleData);
        setTargets(targets);
    }

    @Override
    protected ForwardNatRule self() {
        return this;
    }

    public Set<NatTarget> getTargets() {
        return getData().targets;
    }

    public ForwardNatRule setTargets(Set<NatTarget> targets) {
        if (null == targets || targets.isEmpty())
            throw new IllegalArgumentException(
                    "A forward nat rule must have targets.");
        getData().targets = targets;
        return self();
    }

    public static class Data extends NatRule.Data {

        public transient Set<NatTarget> targets;

        @Override
        public int hashCode() {
            int hash = super.hashCode();
            return 29 * hash + targets.hashCode();
        }

        @Override
        public boolean equals(Object other) {
            if (this == other)
                return true;
            if (!(other instanceof Data))
                return false;
            if (!super.equals(other))
                return false;
            Data r = (Data) other;
            return targets.equals(r.targets);
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder("ForwardNatRule [");
            sb.append(super.toString());
            sb.append(", targets={");
            if(null != targets){
                for (NatTarget t : targets)
                    sb.append(t.toString()).append(", ");
            }
            sb.append("}]");
            return sb.toString();
        }
    }
}
