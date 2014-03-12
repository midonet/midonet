/*
 * Copyright 2012 Midokura KK
 * Copyright 2012 Midokura PTE LTD.
 */
package org.midonet.api.filter;

import org.midonet.cluster.data.rules.ForwardNatRule;
import org.midonet.cluster.data.rules.LiteralRule;
import org.midonet.cluster.data.rules.ReverseNatRule;

public class RuleFactory {

    public static Rule createRule(org.midonet.cluster.data.Rule<?, ?> data) {

        if (data instanceof LiteralRule) {
            LiteralRule typedData = (LiteralRule) data;

            switch(typedData.getAction()) {
                case ACCEPT:
                    return new AcceptRule(typedData);
                case DROP:
                    return new DropRule(typedData);
                case REJECT:
                    return new RejectRule(typedData);
                case RETURN:
                    return new ReturnRule(typedData);
            }
        } else if (data instanceof
                org.midonet.cluster.data.rules.JumpRule) {
            return new JumpRule(
                    (org.midonet.cluster.data.rules.JumpRule) data);
        } else if (data instanceof ForwardNatRule) {
            ForwardNatRule typedData = (ForwardNatRule) data;

            if (typedData.isDnat()) {
                return new ForwardDnatRule(typedData);
            } else {
                return new ForwardSnatRule(typedData);
            }
        } else if (data instanceof ReverseNatRule) {
            ReverseNatRule typedData = (ReverseNatRule) data;

            if (typedData.isDnat()) {
                return new ReverseDnatRule(typedData);
            } else {
                return new ReverseSnatRule(typedData);
            }
        }

        throw new UnsupportedOperationException(
                "Cannot instantiate this rule type.");
    }
}
