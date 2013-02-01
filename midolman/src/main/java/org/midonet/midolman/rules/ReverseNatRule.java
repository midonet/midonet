/*
 * Copyright 2011 Midokura KK
 */

package org.midonet.midolman.rules;

import java.util.UUID;

import org.midonet.midolman.layer4.NatMapping;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.midonet.midolman.layer4.NwTpPair;
import org.midonet.packets.IPv4;
import org.midonet.packets.TCP;
import org.midonet.packets.UDP;
import org.midonet.midolman.rules.RuleResult.Action;


public class ReverseNatRule extends NatRule {

    private final static Logger log = LoggerFactory
            .getLogger(ReverseNatRule.class);
    private static final int USHORT = 0xffff;

    public ReverseNatRule(Condition condition, Action action, boolean dnat) {
        super(condition, action, dnat);
    }

    // default constructor for the JSON serialization.
    public ReverseNatRule() {
        super();
    }

    public ReverseNatRule(Condition condition, Action action, UUID chainId,
            int position, boolean dnat) {
        super(condition, action, chainId, position, dnat);
    }

    @Override
    public void apply(ChainPacketContext fwdInfo, RuleResult res,
                      NatMapping natMapping) {
        // Don't attempt to do port translation on anything but udp/tcp
        byte nwProto = res.pmatch.getNetworkProtocol();
        if (UDP.PROTOCOL_NUMBER != nwProto && TCP.PROTOCOL_NUMBER != nwProto)
            return;

        if (natMapping == null) {
            log.error("Expected NAT mapping to exist");
            return;
        }

        if (dnat)
            applyReverseDnat(res, natMapping);
        else
            applyReverseSnat(res, natMapping);
    }

    private void applyReverseDnat(RuleResult res, NatMapping natMapping) {
        if (null == natMapping)
            return;
        NwTpPair origConn = natMapping.lookupDnatRev(
                res.pmatch.getNetworkDestination(),
                res.pmatch.getTransportDestination(),
                res.pmatch.getNetworkSource(),
                res.pmatch.getTransportSource());
        if (null == origConn)
            return;
        log.debug("Found reverse DNAT. Use SRC {}:{} for flow from {}:{} to "
                + "{}:{}", new Object[] {
                IPv4.fromIPv4Address(origConn.nwAddr), origConn.tpPort & USHORT,
                IPv4.fromIPv4Address(res.pmatch.getNetworkSource()),
                res.pmatch.getTransportSource() & USHORT,
                IPv4.fromIPv4Address(res.pmatch.getNetworkDestination()),
                res.pmatch.getTransportDestination() & USHORT });
        res.pmatch.setNetworkSource(origConn.nwAddr);
        res.pmatch.setTransportSource(origConn.tpPort);
        res.action = action;
    }

    private void applyReverseSnat(RuleResult res, NatMapping natMapping) {
        if (null == natMapping)
            return;
        NwTpPair origConn = natMapping.lookupSnatRev(
                res.pmatch.getNetworkDestination(),
                res.pmatch.getTransportDestination(),
                res.pmatch.getNetworkSource(),
                res.pmatch.getTransportSource());
        if (null == origConn)
            return;
        log.debug("Found reverse SNAT. Use DST {}:{} for flow from {}:{} to "
                + "{}:{}", new Object[] {
                IPv4.fromIPv4Address(origConn.nwAddr), origConn.tpPort & USHORT,
                IPv4.fromIPv4Address(res.pmatch.getNetworkSource()),
                res.pmatch.getTransportSource() & USHORT,
                IPv4.fromIPv4Address(res.pmatch.getNetworkDestination()),
                res.pmatch.getTransportDestination() & USHORT });
        res.pmatch.setNetworkDestination(origConn.nwAddr);
        res.pmatch.setTransportDestination(origConn.tpPort);
        res.action = action;
    }

    @Override
    public int hashCode() {
        return super.hashCode() * 29 + "ReverseNatRule".hashCode();
    }

    @Override
    public boolean equals(Object other) {
        if (this == other)
            return true;
        if (!(other instanceof ReverseNatRule))
            return false;
        return super.equals(other);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("ReverseNatRule [");
        sb.append(super.toString());
        sb.append("]");
        return sb.toString();
    }

}
