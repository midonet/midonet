/*
 * Copyright 2011 Midokura KK
 */

package com.midokura.midolman.rules;

import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.midolman.layer4.NatMapping;
import com.midokura.midolman.layer4.NwTpPair;
import com.midokura.midolman.rules.RuleResult.Action;
import com.midokura.midolman.util.Net;
import com.midokura.packets.IPv4;
import com.midokura.packets.TCP;
import com.midokura.packets.UDP;
import com.midokura.sdn.flows.PacketMatch;


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
    public void apply(Object flowCookie, RuleResult res,
                      NatMapping natMapping) {
        if (null == natMapping)
            return;
        if (dnat)
            applyDnat(flowCookie, res, natMapping);
        else
            applySnat(flowCookie, res, natMapping);
    }

    /**
     * Translate the destination network address (and possibly L4 port).
     *
     * @param flowCookie
     *            An object that uniquely identifies the packet that
     *            originally entered the datapath. Do NOT modify.
     * @param res
     *            contains the match of the packet as seen by this rule,
     *            possibly modified by preceding routers and chains.
     */
    public void applyDnat(Object flowCookie, RuleResult res,
                          NatMapping natMapping) {
        if (floatingIp) {
            log.debug("DNAT mapping floating ip {} to internal ip {}",
                    IPv4.fromIPv4Address(res.pmatch.getNetworkDestination()),
                    IPv4.fromIPv4Address(floatingIpAddr));
            res.pmatch.setNetworkDestination(floatingIpAddr);
            res.action = action;
            return;
        }
        // Don't attempt to do port translation on anything but udp/tcp
        byte nwProto = res.pmatch.getNetworkProtocol();
        if (UDP.PROTOCOL_NUMBER != nwProto && TCP.PROTOCOL_NUMBER != nwProto)
            return;

        NwTpPair conn = natMapping.lookupDnatFwd(
                res.pmatch.getNetworkSource(),
                res.pmatch.getTransportSource(),
                res.pmatch.getNetworkDestination(),
                res.pmatch.getTransportDestination(), flowCookie);
        if (null == conn)
            conn = natMapping.allocateDnat(res.pmatch.getNetworkSource(),
                    res.pmatch.getTransportSource(),
                    res.pmatch.getNetworkDestination(),
                    res.pmatch.getTransportDestination(), targets, flowCookie);
        else
            log.debug("Found existing forward DNAT {}:{} for flow from {}:{} "
                    + "to {}:{}", new Object[] {
                    IPv4.fromIPv4Address(conn.nwAddr), conn.tpPort & USHORT,
                    IPv4.fromIPv4Address(res.pmatch.getNetworkSource()),
                    res.pmatch.getTransportSource() & USHORT,
                    IPv4.fromIPv4Address(res.pmatch.getNetworkDestination()),
                    res.pmatch.getTransportDestination() & USHORT });
        // TODO(pino): deal with case that conn couldn't be allocated.
        res.pmatch.setNetworkDestination(conn.nwAddr);
        res.pmatch.setTransportDestination(conn.tpPort);
        res.action = action;
        res.trackConnection = true;
    }

    /**
     * Translate the destination network address (and possibly L4 port).
     *
     * @param flowCookie
     *            An object that uniquely identifies the packet that
     *            originally entered the datapath. Do NOT modify.
     * @param res
     *            contains the match of the packet as seen by this rule,
     *            possibly modified by preceding routers and chains.
     */
    public void applySnat(Object flowCookie, RuleResult res,
                          NatMapping natMapping) {
        if (floatingIp) {
            log.debug("SNAT mapping internal ip {} to floating ip {}",
                    IPv4.fromIPv4Address(res.pmatch.getNetworkSource()),
                    IPv4.fromIPv4Address(floatingIpAddr));
            res.pmatch.setNetworkSource(floatingIpAddr);
            res.action = action;
            return;
        }
        // Don't attempt to do port translation on anything but udp/tcp
        byte nwProto = res.pmatch.getNetworkProtocol();
        if (UDP.PROTOCOL_NUMBER != nwProto && TCP.PROTOCOL_NUMBER != nwProto)
            return;

        NwTpPair conn = natMapping.lookupSnatFwd(
                res.pmatch.getNetworkSource(),
                res.pmatch.getTransportSource(),
                res.pmatch.getNetworkDestination(),
                res.pmatch.getTransportDestination(), flowCookie);
        if (null == conn)
            conn = natMapping.allocateSnat(res.pmatch.getNetworkSource(),
                    res.pmatch.getTransportSource(),
                    res.pmatch.getNetworkDestination(),
                    res.pmatch.getTransportDestination(), targets, flowCookie);
        else
            log.debug("Found existing forward SNAT {}:{} for flow from {}:{} "
                    + "to {}:{}", new Object[] {
                    IPv4.fromIPv4Address(conn.nwAddr), conn.tpPort & USHORT,
                    IPv4.fromIPv4Address(res.pmatch.getNetworkSource()),
                    res.pmatch.getTransportSource() & USHORT,
                    IPv4.fromIPv4Address(res.pmatch.getNetworkDestination()),
                    res.pmatch.getTransportDestination() & USHORT});

        if (conn == null) {
            log.error("Could not allocate Snat");
            return;
        }
        res.pmatch.setNetworkSource(conn.nwAddr);
        res.pmatch.setTransportSource(conn.tpPort);
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
