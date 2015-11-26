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

public class ForwardNatRule extends NatRule {
    @ZoomField(name = "nat_targets")
    protected transient Set<NatTarget> targetsSet;
    protected transient NatTarget[] targets;
    private boolean floatingIp;
    private IPAddr floatingIpAddr;
    private final static Logger log = LoggerFactory
            .getLogger(ForwardNatRule.class);

    // Default constructor for the Jackson deserialization.
    // This constructor is also used by ZoomConvert.
    public ForwardNatRule() {
        super();
    }

    public ForwardNatRule(Condition condition, Action action, UUID chainId,
                          boolean dnat, Set<NatTarget> targets) {
        super(condition, action, chainId, dnat);
        if (targets == null || targets.isEmpty())
            throw new IllegalArgumentException(
                    "A forward nat rule must have targets.");
        setNatTargets(targets);
        floatingIp = false;
        if (targets.size() == 1) {
            NatTarget tg = targets.iterator().next();
            if (tg.nwStart.equals(tg.nwEnd) && 0 == tg.tpStart && 0 == tg.tpEnd) {
                floatingIp = true;
                floatingIpAddr = tg.nwStart;
            }
        }

        log.debug("Created a {} forward nat rule",
                  (floatingIp) ? "FloatingIp" : "normal");
    }

    public boolean isFloatingIp() {
        return floatingIp;
    }

    @VisibleForTesting
    public NatTarget[] getTargetsArray() {
        return targets;
    }

    public void afterFromProto(Message message) {
        super.afterFromProto(message);

        setNatTargets(targetsSet);
        floatingIp = false;
        if (targetsSet.size() == 1) {
            NatTarget tg = targetsSet.iterator().next();
            if (tg.nwStart.equals(tg.nwEnd) && 0 == tg.tpStart && 0 == tg.tpEnd) {
                floatingIp = true;
                floatingIpAddr = tg.nwStart;
            }
        }

        log.debug("Created a {} forward nat rule",
                  (floatingIp) ? "FloatingIp" : "normal");
    }

    @Override
    public boolean apply(PacketContext pktCtx) {
        return dnat
             ? applyDnat(pktCtx)
             : applySnat(pktCtx);
    }

    protected boolean applyDnat(PacketContext pktCtx) {
        if (floatingIp) {
            pktCtx.jlog().debug("DNAT mapping internal ip {} to floating ip {}",
                                pktCtx.wcmatch().getNetworkDstIP(), floatingIpAddr);
            pktCtx.wcmatch().setNetworkDst(floatingIpAddr);
            return true;
        } else {
            return pktCtx.applyDnat(targets);
        }
    }

    protected boolean applySnat(PacketContext pktCtx) {
        if (floatingIp) {
            pktCtx.jlog().debug("SNAT mapping internal ip {} to floating ip {}",
                                pktCtx.wcmatch().getNetworkSrcIP(), floatingIpAddr);
            pktCtx.wcmatch().setNetworkSrc(floatingIpAddr);
            return true;
        } else {
            return pktCtx.applySnat(targets);
        }
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

    // Getter for JSON serialization supporting readable IP addresses
    public String getFloatingIpAddr() {
        // TODO (galo) not sure this is what we want, but it's what was
        // happening when floatingIpAddr was an unitialized int
        return (this.floatingIpAddr == null) ? "0.0.0.0" :
                this.floatingIpAddr.toString();
    }

    // Setter for JSON serialization supporting readable IP addresses
    public void setFloatingIpAddr(String addr) {
        this.floatingIpAddr = IPAddr$.MODULE$.fromString(addr);
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
        if (!(other instanceof ForwardNatRule))
            return false;
        if (!super.equals(other))
            return false;
        ForwardNatRule r = (ForwardNatRule) other;
        return Arrays.equals(targets, r.targets);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("ForwardNatRule [");
        sb.append(super.toString());
        sb.append(", floatingIp=").append(floatingIp);
        if(floatingIp) {
            sb.append(", floatingIpAddr=");
            sb.append(floatingIpAddr);
        }
        sb.append(", targets={");
        if(null != targets){
            for (NatTarget t : targets)
                sb.append(t.toString()).append(", ");
        }
        return sb.append("}]").toString();
    }
}
