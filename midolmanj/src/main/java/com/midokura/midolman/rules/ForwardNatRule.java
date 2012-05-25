/*
 * Copyright 2011 Midokura KK
 */

package com.midokura.midolman.rules;

import java.util.Set;
import java.util.UUID;

import com.midokura.midolman.layer4.NatMapping;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.midolman.layer4.NwTpPair;
import com.midokura.midolman.openflow.MidoMatch;
import com.midokura.midolman.packets.IPv4;
import com.midokura.midolman.packets.TCP;
import com.midokura.midolman.packets.UDP;
import com.midokura.midolman.rules.RuleResult.Action;
import com.midokura.midolman.util.Net;

public class ForwardNatRule extends NatRule {
    protected transient Set<NatTarget> targets;
    private boolean floatingIp;
    private int floatingIpAddr;
    private final static Logger log = LoggerFactory
            .getLogger(ForwardNatRule.class);
    private static final int USHORT = 0xffff;

    // Default constructor for the Jackson deserialization.
    public ForwardNatRule() {
        super();
    }

    public ForwardNatRule(Condition condition, Action action, UUID chainId,
            int position, boolean dnat, Set<NatTarget> targets) {
        super(condition, action, chainId, position, dnat);
        this.targets = targets;
        if (null == targets || targets.size() == 0)
            throw new IllegalArgumentException(
                    "A forward nat rule must have targets.");
        floatingIp = false;
        if (targets.size() == 1) {
            NatTarget tg = targets.iterator().next();
            if (tg.nwStart == tg.nwEnd && 0 == tg.tpStart && 0 == tg.tpStart) {
                floatingIp = true;
                floatingIpAddr = tg.nwStart;
            }
        }
    }

    @Override
    public void apply(MidoMatch flowMatch, RuleResult res,
                      NatMapping natMapping) {
        if (null == natMapping)
            return;
        if (dnat)
            applyDnat(flowMatch, res, natMapping);
        else
            applySnat(flowMatch, res, natMapping);
    }

    /**
     * Translate the destination network address (and possibly L4 port).
     *
     * @param flowMatch
     *            the original match of the packet that entered the datapath. Do
     *            NOT modify.
     * @param res
     *            contains the match of the packet as seen by this rule,
     *            possibly modified by preceding routers and chains.
     */
    public void applyDnat(MidoMatch flowMatch, RuleResult res,
                          NatMapping natMapping) {
        if (floatingIp) {
            log.debug("DNAT mapping floating ip {} to internal ip {}",
                    IPv4.fromIPv4Address(res.match.getNetworkDestination()),
                    IPv4.fromIPv4Address(floatingIpAddr));
            res.match.setNetworkDestination(floatingIpAddr);
            res.action = action;
            return;
        }
        // Don't attempt to do port translation on anything but udp/tcp
        byte nwProto = res.match.getNetworkProtocol();
        if (UDP.PROTOCOL_NUMBER != nwProto && TCP.PROTOCOL_NUMBER != nwProto)
            return;

        NwTpPair conn = natMapping.lookupDnatFwd(res.match.getNetworkSource(),
                res.match.getTransportSource(), res.match
                        .getNetworkDestination(), res.match
                        .getTransportDestination(), flowMatch);
        if (null == conn)
            conn = natMapping.allocateDnat(res.match.getNetworkSource(),
                    res.match.getTransportSource(),
                    res.match.getNetworkDestination(),
                    res.match.getTransportDestination(), targets, flowMatch);
        else
            log.debug("Found existing forward DNAT {}:{} for flow from {}:{} "
                    + "to {}:{}", new Object[] {
                    IPv4.fromIPv4Address(conn.nwAddr), conn.tpPort & USHORT,
                    IPv4.fromIPv4Address(res.match.getNetworkSource()),
                    res.match.getTransportSource() & USHORT,
                    IPv4.fromIPv4Address(res.match.getNetworkDestination()),
                    res.match.getTransportDestination() & USHORT });
        // TODO(pino): deal with case that conn couldn't be allocated.
        res.match.setNetworkDestination(conn.nwAddr);
        res.match.setTransportDestination(conn.tpPort);
        res.action = action;
        res.trackConnection = true;
    }

    /**
     * Translate the destination network address (and possibly L4 port).
     *
     * @param flowMatch
     *            the original match of the packet that entered the datapath. Do
     *            NOT modify.
     * @param res
     *            contains the match of the packet as seen by this rule,
     *            possibly modified by preceding routers and chains.
     */
    public void applySnat(MidoMatch flowMatch, RuleResult res,
                          NatMapping natMapping) {
        if (floatingIp) {
            log.debug("SNAT mapping internal ip {} to floating ip {}",
                    IPv4.fromIPv4Address(res.match.getNetworkSource()),
                    IPv4.fromIPv4Address(floatingIpAddr));
            res.match.setNetworkSource(floatingIpAddr);
            res.action = action;
            return;
        }
        // Don't attempt to do port translation on anything but udp/tcp
        byte nwProto = res.match.getNetworkProtocol();
        if (UDP.PROTOCOL_NUMBER != nwProto && TCP.PROTOCOL_NUMBER != nwProto)
            return;

        NwTpPair conn = natMapping.lookupSnatFwd(res.match.getNetworkSource(),
                res.match.getTransportSource(), res.match
                        .getNetworkDestination(), res.match
                        .getTransportDestination(), flowMatch);
        if (null == conn)
            conn = natMapping.allocateSnat(res.match.getNetworkSource(),
                    res.match.getTransportSource(),
                    res.match.getNetworkDestination(),
                    res.match.getTransportDestination(), targets, flowMatch);
        else
            log.debug("Found existing forward SNAT {}:{} for flow from {}:{} "
                    + "to {}:{}", new Object[] {
                    IPv4.fromIPv4Address(conn.nwAddr), conn.tpPort & USHORT,
                    IPv4.fromIPv4Address(res.match.getNetworkSource()),
                    res.match.getTransportSource() & USHORT,
                    IPv4.fromIPv4Address(res.match.getNetworkDestination()),
                    res.match.getTransportDestination() & USHORT});

        if (conn == null) {
            log.error("Could not allocate Snat");
            return;
        }
        res.match.setNetworkSource(conn.nwAddr);
        res.match.setTransportSource(conn.tpPort);
        res.action = action;
        res.trackConnection = true;
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
    	return Net.convertIntAddressToString(this.floatingIpAddr);
    }

    // Setter for JSON serialization supporting readable IP addresses
    public void setFloatingIpAddr(String addr) {
    	this.floatingIpAddr = Net.convertStringAddressToInt(addr);
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
            sb.append(IPv4.fromIPv4Address(floatingIpAddr));
        }
        sb.append(", targets={");
        if(null != targets){
            for (NatTarget t : targets)
                sb.append(t.toString()).append(", ");
        }
        sb.append("}]");
        return sb.toString();
    }
}
