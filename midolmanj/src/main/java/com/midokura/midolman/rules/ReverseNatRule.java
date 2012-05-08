/*
 * Copyright 2011 Midokura KK
 */

package com.midokura.midolman.rules;

import java.util.UUID;

import com.midokura.midolman.layer4.NatMapping;
import org.apache.cassandra.gms.IFailureDetectionEventListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.midolman.layer4.NwTpPair;
import com.midokura.midolman.openflow.MidoMatch;
import com.midokura.midolman.packets.IPv4;
import com.midokura.midolman.packets.TCP;
import com.midokura.midolman.packets.UDP;
import com.midokura.midolman.rules.RuleResult.Action;

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
    public void apply(MidoMatch flowMatch, UUID inPortId, UUID outPortId,
            RuleResult res, NatMapping natMapping) {
        // Don't attempt to do port translation on anything but udp/tcp
        byte nwProto = res.match.getNetworkProtocol();
        if (UDP.PROTOCOL_NUMBER != nwProto && TCP.PROTOCOL_NUMBER != nwProto)
            return;

        if (natMapping == null) {
            log.error("Expected NAT mapping to exist");
            return;
        }

        if (dnat)
            applyReverseDnat(inPortId, outPortId, res, natMapping);
        else
            applyReverseSnat(inPortId, outPortId, res, natMapping);
    }

    private void applyReverseDnat(UUID inPortId, UUID outPortId, RuleResult res,
                                  NatMapping natMapping) {
        if (null == natMapping)
            return;
        NwTpPair origConn = natMapping.lookupDnatRev(res.match
                .getNetworkDestination(), res.match.getTransportDestination(),
                res.match.getNetworkSource(), res.match.getTransportSource());
        if (null == origConn)
            return;
        log.debug("Found reverse DNAT. Use SRC {}:{} for flow from {}:{} to "
                + "{}:{}", new Object[] {
                IPv4.fromIPv4Address(origConn.nwAddr), origConn.tpPort & USHORT,
                IPv4.fromIPv4Address(res.match.getNetworkSource()),
                res.match.getTransportSource() & USHORT,
                IPv4.fromIPv4Address(res.match.getNetworkDestination()),
                res.match.getTransportDestination() & USHORT });
        res.match.setNetworkSource(origConn.nwAddr);
        res.match.setTransportSource(origConn.tpPort);
        res.action = action;
    }

    private void applyReverseSnat(UUID inPortId, UUID outPortId, RuleResult res,
                                  NatMapping natMapping) {
        if (null == natMapping)
            return;
        NwTpPair origConn = natMapping.lookupSnatRev(res.match
                .getNetworkDestination(), res.match.getTransportDestination(),
                res.match.getNetworkSource(), res.match.getTransportSource());
        if (null == origConn)
            return;
        log.debug("Found reverse SNAT. Use DST {}:{} for flow from {}:{} to "
                + "{}:{}", new Object[] {
                IPv4.fromIPv4Address(origConn.nwAddr), origConn.tpPort & USHORT,
                IPv4.fromIPv4Address(res.match.getNetworkSource()),
                res.match.getTransportSource() & USHORT,
                IPv4.fromIPv4Address(res.match.getNetworkDestination()),
                res.match.getTransportDestination() & USHORT });
        res.match.setNetworkDestination(origConn.nwAddr);
        res.match.setTransportDestination(origConn.tpPort);
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
