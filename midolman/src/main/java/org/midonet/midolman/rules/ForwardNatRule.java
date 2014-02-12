/*
 * Copyright 2011 Midokura KK
 */

package org.midonet.midolman.rules;

import java.util.Set;
import java.util.UUID;

import org.midonet.midolman.layer4.NatMapping;
import org.midonet.midolman.layer4.NwTpPair;
import org.midonet.midolman.rules.RuleResult.Action;
import org.midonet.packets.ICMP;
import org.midonet.packets.IPAddr;
import org.midonet.packets.IPAddr$;
import org.midonet.packets.IPv4;
import org.midonet.packets.IPv4Addr;
import org.midonet.packets.MalformedPacketException;
import org.midonet.packets.Net;
import org.midonet.sdn.flows.WildcardMatch;
import org.midonet.util.functors.Callback0;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public class ForwardNatRule extends NatRule {
    protected transient Set<NatTarget> targets;
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
        this.targets = targets;
        if (null == targets || targets.isEmpty())
            throw new IllegalArgumentException(
                    "A forward nat rule must have targets.");
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
    public void apply(ChainPacketContext fwdInfo, RuleResult res,
                      NatMapping natMapping) {
        if (null == natMapping)
            log.debug("Cannot apply a ForwardNatRule without a NatMapping.");
        else
            try {
                if (dnat)
                    applyDnat(fwdInfo, res, natMapping);
                else
                    applySnat(fwdInfo, res, natMapping);
            } catch (MalformedPacketException e) {
                log.error("Errors applying forward NAT {}", e);
            }
    }

    private Callback0 makeUnrefCallback(final NatMapping mapping,
                                        final String key) {
        return new Callback0() {
            @Override
            public void call() {
                mapping.natUnref(key);
            }
        };
    }

    /**
     * Translate the destination network address (and possibly L4 port).
     *
     * @param res contains the match of the packet as seen by this rule,
     *            possibly modified by preceding routers and chains.
     */
    private void applyDnat(ChainPacketContext fwdInfo, RuleResult res,
                           final NatMapping natMapping)
        throws MalformedPacketException {

        WildcardMatch match = res.pmatch;

        if (floatingIp) {
            log.debug("DNAT mapping floating ip {} to internal ip {}",
                    match.getNetworkDestinationIP(), floatingIpAddr);
                    match.setNetworkDestination(floatingIpAddr);
            res.action = action;
            return;
        }
        log.debug("applying a dnat to {}", res);

        if (!isNatSupported(match))
            return;

        NatLookupTuple tp = getTpForMappingLookup(match);

        NwTpPair conn = natMapping.lookupDnatFwd(tp.proto,
                                                 tp.nwSrc, tp.tpSrc,
                                                 tp.nwDst, tp.tpDst);
        if (null == conn) {
            conn = natMapping.allocateDnat(tp.proto,
                                           tp.nwSrc, tp.tpSrc,
                                           tp.nwDst, tp.tpDst, targets);
        } else {
            log.debug("Found existing forward DNAT {}:{} for flow from {}:{} "
                    + "to {}:{}, protocol {}", new Object[] {
                    conn.nwAddr, conn.tpPort & USHORT,
                    tp.nwSrc, tp.tpSrc, tp.nwDst, tp.tpDst, tp.proto});
        }
        // TODO(pino): deal with case that conn couldn't be allocated.
        match.setNetworkDestination(conn.nwAddr);
        if (tp.proto != ICMP.PROTOCOL_NUMBER) {
            match.setTransportDestination(conn.tpPort);
        }
        res.action = action;

        fwdInfo.addFlowRemovedCallback(makeUnrefCallback(natMapping, conn.unrefKey));
    }

    /**
     * Translate the destination network address (and possibly L4 port).
     *
     * @param res contains the match of the packet as seen by this rule,
     *            possibly modified by preceding routers and chains.
     */
    private void applySnat(ChainPacketContext fwdInfo,  RuleResult res,
                           final NatMapping natMapping)
        throws MalformedPacketException {

        WildcardMatch match = res.pmatch;

        if (floatingIp) {
            log.debug("SNAT mapping internal ip {} to floating ip {}",
                    match.getNetworkSourceIP(), floatingIpAddr);
                    match.setNetworkSource(floatingIpAddr);
            res.action = action;
            return;
        }

        if (!isNatSupported(match))
            return;

        NatLookupTuple tp = getTpForMappingLookup(match);

        NwTpPair conn = natMapping.lookupSnatFwd(tp.proto,
                                                 tp.nwSrc, tp.tpSrc,
                                                 tp.nwDst, tp.tpDst);
        if (null == conn) {
            conn = natMapping.allocateSnat(tp.proto,
                                           tp.nwSrc, tp.tpSrc,
                                           tp.nwDst, tp.tpDst, targets);
        } else {
            log.debug("Found existing forward SNAT {}:{} for flow from {}:{} "
                    + "to {}:{}, protocol {}", new Object[] {
                    conn.nwAddr, conn.tpPort & USHORT, tp.nwSrc, tp.tpSrc,
                    tp.nwDst, tp.tpDst, tp.proto});
        }

        if (conn == null) {
            log.error("Could not allocate srcNAT");
            return;
        }
        match.setNetworkSource(conn.nwAddr);
        if (tp.proto != ICMP.PROTOCOL_NUMBER) {
            match.setTransportSource(conn.tpPort);
        }
        res.action = action;

        fwdInfo.addFlowRemovedCallback(makeUnrefCallback(natMapping,
                                                         conn.unrefKey));
    }

    // Used by RuleEngine to discover resources that must be initialized
    // or preserved. Not all NatRules have NatTargets (e.g. reverse nats).
    public Set<NatTarget> getNatTargets() {
        return targets;
    }

    // Setter for the JSON serialization.
    public void setNatTargets(Set<NatTarget> targets) {
        this.targets = targets;
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
        return 29 * hash + targets.hashCode();
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
        return targets.equals(r.targets);
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
