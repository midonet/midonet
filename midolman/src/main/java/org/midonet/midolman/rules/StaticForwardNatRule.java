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

import java.util.Set;
import java.util.UUID;

import com.google.protobuf.Message;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.midonet.cluster.data.ZoomField;
import org.midonet.midolman.rules.RuleResult.Action;
import org.midonet.midolman.simulation.PacketContext;
import org.midonet.packets.IPAddr;
import org.midonet.packets.IPv4Addr;

public class StaticForwardNatRule extends NatRule {
    @ZoomField(name = "nat_targets")
    protected transient Set<NatTarget> targetsSet;

    private IPAddr targetIpAddr;
    private final static Logger log = LoggerFactory
            .getLogger(StaticForwardNatRule.class);

    // Default constructor for the Jackson deserialization.
    // This constructor is also used by ZoomConvert.
    public StaticForwardNatRule() {
        super();
    }

    public StaticForwardNatRule(Condition condition, Action action, UUID chainId,
                                boolean dnat, Set<NatTarget> targets) {
        super(condition, action, chainId, dnat);

        setNatTargets(targets);

        log.debug("Created a floating-IP forward static NAT rule");
    }

    public void afterFromProto(Message message) {
        super.afterFromProto(message);

        setNatTargets(targetsSet);

        log.debug("Created a floating-IP forward static NAT rule");
    }

    @Override
    public boolean apply(PacketContext pktCtx) {
        return dnat
             ? applyDnat(pktCtx)
             : applySnat(pktCtx);
    }

    private boolean applyDnat(PacketContext pktCtx) {
        if (condition.icmpDataSrcIp == null && condition.icmpDataDstIp == null) {
            pktCtx.jlog().debug("DNAT mapping packet destination IP {} to target IP {}",
                                pktCtx.wcmatch().getNetworkDstIP(), targetIpAddr);
            pktCtx.wcmatch().setNetworkDst(targetIpAddr);
        }

        if (pktCtx.isIcmp()) {
            if (condition.icmpDataDstIp != null) {
                pktCtx.jlog().debug("Mapping ICMP data destination IP {} to target IP {}",
                                    condition.icmpDataDstIp.getAddress(), targetIpAddr);
                pktCtx.dnatOnICMPData((IPv4Addr)condition.icmpDataDstIp.getAddress(),
                                      (IPv4Addr)targetIpAddr);
            } else if (condition.nwDstIp != null) {
                pktCtx.jlog().debug("Mapping ICMP data source IP {} to target IP {}",
                                    condition.nwDstIp.getAddress(), targetIpAddr);
                pktCtx.snatOnICMPData((IPv4Addr)condition.nwDstIp.getAddress(),
                                      (IPv4Addr)targetIpAddr);
            }
        }
        return true;
    }

    private boolean applySnat(PacketContext pktCtx) {
        pktCtx.jlog().debug("SNAT mapping packet source IP {} to target IP {}",
                            pktCtx.wcmatch().getNetworkSrcIP(), targetIpAddr);
        pktCtx.wcmatch().setNetworkSrc(targetIpAddr);

        if (pktCtx.isIcmp() && condition.nwSrcIp != null) {
            pktCtx.jlog().debug("Mapping ICMP data destination IP {} to target IP {}",
                                condition.nwSrcIp.getAddress(), targetIpAddr);
            pktCtx.dnatOnICMPData((IPv4Addr)condition.nwSrcIp.getAddress(),
                                  (IPv4Addr)targetIpAddr);
        }

        return true;
    }

    // Used by RuleEngine to discover resources that must be initialized
    // or preserved. Not all NatRules have NatTargets (e.g. reverse nats).
    public Set<NatTarget> getNatTargets() {
        return targetsSet;
    }

    // Setter for the JSON serialization.
    public void setNatTargets(Set<NatTarget> targets) {
        if (targets == null || targets.isEmpty() || targets.size() != 1)
            throw new IllegalArgumentException(
                    "A forward static NAT rule must have a single target.");
        targetsSet = targets;

        NatTarget tg = targetsSet.iterator().next();
        targetIpAddr = tg.nwStart;
    }

    // Getter for JSON serialization supporting readable IP addresses
    public String getTargetIpAddr() {
        // TODO (galo) not sure this is what we want, but it's what was
        // happening when targetIpAddr was an unitialized int
        return (this.targetIpAddr == null) ? "0.0.0.0" :
                this.targetIpAddr.toString();
    }

    @Override
    public int hashCode() {
        int hash = super.hashCode();
        return 29 * hash + targetIpAddr.hashCode();
    }

    @Override
    public boolean equals(Object other) {
        if (this == other)
            return true;
        if (!(other instanceof StaticForwardNatRule))
            return false;
        if (!super.equals(other))
            return false;
        StaticForwardNatRule r = (StaticForwardNatRule) other;
        return this.targetIpAddr != null
            && this.targetIpAddr.equals(r.targetIpAddr);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("StaticForwardNatRule [");
        sb.append(super.toString());
        sb.append(", targetIpAddr=").append(targetIpAddr);
        return sb.append("]").toString();
    }
}
