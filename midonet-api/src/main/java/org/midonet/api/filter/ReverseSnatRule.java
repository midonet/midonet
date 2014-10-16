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
package org.midonet.api.filter;

import org.midonet.cluster.data.rules.ReverseNatRule;

/**
 * Reverse SNAT rule DTO
 */
public class ReverseSnatRule extends NatRule {

    public ReverseSnatRule() {
        super();
    }

    public ReverseSnatRule(
            org.midonet.cluster.data.rules.ReverseNatRule rule) {
        super(rule);
        if (rule.isDnat()) {
            throw new IllegalArgumentException("Invalid argument passed in.");
        }
    }

    @Override
    public String getType() {
        return RuleType.RevSNAT;
    }

    @Override
    public ReverseNatRule toData () {
        ReverseNatRule data =
                new ReverseNatRule(makeCondition(), getNatFlowAction(), false);
        super.setData(data);
        return data;
    }
}
