/*
 * Copyright (c) 2011 Midokura SARL, All Rights Reserved.
 */

package org.midonet.midolman.rules;

import java.util.Arrays;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.midonet.midolman.layer4.NatMapping;
import org.midonet.midolman.rules.RuleResult.Action;
import org.midonet.midolman.simulation.PacketContext;
import org.midonet.packets.*;

public class ForwardNatRule extends NatRule {
    protected transient Set<NatTarget> targetsSet;
    protected transient NatTarget[] targets;
    private boolean floatingIp;
    private IPAddr floatingIpAddr;
    private final static Logger log = LoggerFactory
            .getLogger(ForwardNatRule.class);

    // Default constructor for the Jackson deserialization.
    public ForwardNatRule() {
        super();
    }

    public ForwardNatRule(Condition condition, Action action, UUID chainId,
            int position, boolean dnat, Set<NatTarget> targets) {
        super(condition, action, chainId, position, dnat);
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

    @Override
    public void apply(PacketContext pktCtx, RuleResult res,
                      NatMapping natMapping) {
        boolean gotNat = dnat ? applyDnat(pktCtx)
                              : applySnat(pktCtx);
        if (gotNat)
            res.action = action;
    }

    protected boolean applyDnat(PacketContext pktCtx) {
        if (floatingIp) {
            log.debug("DNAT mapping floating ip {} to internal ip {}",
                      pktCtx.wcmatch().getNetworkDestinationIP(), floatingIpAddr);
            pktCtx.wcmatch().setNetworkDestination(floatingIpAddr);
            return true;
        } else {
            return pktCtx.state().applyDnat(targets);
        }
    }

    protected boolean applySnat(PacketContext pktCtx) {
        if (floatingIp) {
            log.debug("DNAT mapping floating ip {} to internal ip {}",
                    pktCtx.wcmatch().getNetworkSourceIP(), floatingIpAddr);
            pktCtx.wcmatch().setNetworkSource(floatingIpAddr);
            return true;
        } else {
            return pktCtx.state().applySnat(targets);
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
