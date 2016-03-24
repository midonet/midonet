/*
 * Copyright 2016 Midokura SARL
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

import java.util.Arrays;
import java.util.Set;
import java.util.UUID;

import com.google.common.annotations.VisibleForTesting;
import com.google.protobuf.Message;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.midonet.cluster.data.ZoomField;
import org.midonet.midolman.rules.RuleResult.Action;
import org.midonet.midolman.simulation.PacketContext;
import org.midonet.packets.IPAddr;
import org.midonet.packets.IPAddr$;

public class DynamicForwardNatRule extends NatRule {
    @ZoomField(name = "nat_targets")
    protected transient Set<NatTarget> targetsSet;
    protected transient NatTarget[] targets;

    private final static Logger log = LoggerFactory
            .getLogger(DynamicForwardNatRule.class);

    // Default constructor for the Jackson deserialization.
    // This constructor is also used by ZoomConvert.
    public DynamicForwardNatRule() {
        super();
    }

    public DynamicForwardNatRule(Condition condition, Action action, UUID chainId,
                          boolean dnat, Set<NatTarget> targets) {
        super(condition, action, chainId, dnat);
        if (targets == null || targets.isEmpty())
            throw new IllegalArgumentException(
                    "A forward nat rule must have targets.");
        setNatTargets(targets);

        log.debug("Created a normal forward nat rule");
    }

    @VisibleForTesting
    public NatTarget[] getTargetsArray() {
        return targets;
    }

    public void afterFromProto(Message message) {
        super.afterFromProto(message);

        setNatTargets(targetsSet);

        log.debug("Created a normal forward nat rule");
    }

    @Override
    public boolean apply(PacketContext pktCtx) {
        return dnat
            ? pktCtx.applyDnat(targets)
            : pktCtx.applySnat(targets);
    }

    // Used by RuleEngine to discover resources that must be initialized
    // or preserved. Not all NatRules have NatTargets (e.g. reverse nats).
    public Set<NatTarget> getNatTargets() {
        return targetsSet;
    }

    // Setter for the JSON serialization.
    public void setNatTargets(Set<NatTarget> targets) {
        targetsSet = targets;
        this.targets = targets.toArray(new NatTarget[targets.size()]);
    }

    @Override
    public int hashCode() {
        int hash = super.hashCode();
        return 29 * hash + Arrays.hashCode(targets);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other)
            return true;
        if (!(other instanceof DynamicForwardNatRule))
            return false;
        if (!super.equals(other))
            return false;
        DynamicForwardNatRule r = (DynamicForwardNatRule) other;
        return Arrays.equals(targets, r.targets);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("DynamicForwardNatRule [");
        sb.append(super.toString());
        sb.append(", targets={");
        if(null != targets){
            for (NatTarget t : targets)
                sb.append(t.toString()).append(", ");
        }
        return sb.append("}]").toString();
    }
}
