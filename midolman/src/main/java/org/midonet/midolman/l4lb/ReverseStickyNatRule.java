/*
 * Copyright (c) 2014 Midokura Europe SARL, All Rights Reserved.
 */
package org.midonet.midolman.l4lb;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.midonet.midolman.layer4.NatMapping;
import org.midonet.midolman.layer4.NwTpPair;
import org.midonet.midolman.rules.Condition;
import org.midonet.midolman.rules.ReverseNatRule;
import org.midonet.midolman.rules.RuleResult;
import org.midonet.packets.ICMP;
import org.midonet.packets.MalformedPacketException;
import org.midonet.sdn.flows.WildcardMatch;


public class ReverseStickyNatRule extends ReverseNatRule {
    private final static Logger log = LoggerFactory
            .getLogger(ReverseStickyNatRule.class);

    public final static int WILDCARD_PORT = 0;

    public ReverseStickyNatRule(Condition condition,
                                RuleResult.Action action,
                                boolean dnat) {
        super(condition, action, dnat);
    }

    // default constructor for the JSON serialization.
    public ReverseStickyNatRule() {
        super();
    }

    @Override
    protected void applyReverseDnat(RuleResult res, NatMapping natMapping)
        throws MalformedPacketException {

            WildcardMatch match = res.pmatch;

            NatLookupTuple tp = getTpForMappingLookup(match);

            NwTpPair origConn = natMapping.lookupDnatRev(tp.proto,
                    tp.nwDst, WILDCARD_PORT,
                    tp.nwSrc, tp.tpSrc);

            if (null == origConn)
                return;

            log.debug("Found reverse sticky DNAT. Use SRC {}:{} for flow from {}:{} to "
                    + "{}:{}, protocol {}", new Object[] {
                    origConn.nwAddr, origConn.tpPort & USHORT,
                    tp.nwSrc, tp.tpSrc, tp.nwDst, tp.tpDst, tp.proto});

            // We don't handle ICMP for sticky NAT
            if (match.getNetworkProtocol() != ICMP.PROTOCOL_NUMBER) {
                match.setNetworkSource(origConn.nwAddr);
                match.setTransportSource(origConn.tpPort);
            }

            res.action = action;
    }
}
