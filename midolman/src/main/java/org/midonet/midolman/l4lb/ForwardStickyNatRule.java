/*
 * Copyright (c) 2014 Midokura Europe SARL, All Rights Reserved.
 */
package org.midonet.midolman.l4lb;

import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.midonet.midolman.layer4.NatMapping;
import org.midonet.midolman.layer4.NwTpPair;
import org.midonet.midolman.rules.ChainPacketContext;
import org.midonet.midolman.rules.Condition;
import org.midonet.midolman.rules.ForwardNatRule;
import org.midonet.midolman.rules.NatTarget;
import org.midonet.midolman.rules.RuleResult;
import org.midonet.packets.ICMP;
import org.midonet.packets.MalformedPacketException;
import org.midonet.sdn.flows.WildcardMatch;

import static org.midonet.midolman.l4lb.ReverseStickyNatRule.WILDCARD_PORT;

public class ForwardStickyNatRule extends ForwardNatRule {
    private int stickyIPTimeout;
    private final static Logger log = LoggerFactory
            .getLogger(ForwardStickyNatRule.class);

    public ForwardStickyNatRule(Condition condition, RuleResult.Action action,
                                UUID chainId, int position, boolean dnat,
                                Set<NatTarget> targets, int stickyIPTimeout) {
        super(condition, action, chainId, position, dnat, targets);
        this.stickyIPTimeout = stickyIPTimeout;
    }

    @Override
    protected void applyDnat(ChainPacketContext fwdInfo, RuleResult res,
                   final NatMapping natMapping)
            throws MalformedPacketException {

        WildcardMatch match = res.pmatch;

        log.debug("Applying a sticky dnat to {}", res);

        if (!isNatSupported(match))
            return;

        NatLookupTuple tp = getTpForMappingLookup(match);

        NwTpPair conn = null;

        conn = natMapping.lookupDnatFwd(tp.proto,
                tp.nwSrc, WILDCARD_PORT,
                tp.nwDst, tp.tpDst, stickyIPTimeout);

        if (null == conn) {
            conn = natMapping.allocateDnat(tp.proto,
                    tp.nwSrc, WILDCARD_PORT,
                    tp.nwDst, tp.tpDst, targets, stickyIPTimeout);
        } else {
            log.debug("Found existing sticky forward DNAT {}:{} for flow from "
                    + "{}:{} to {}:{}, protocol {}", new Object[] {
                    conn.nwAddr, conn.tpPort & USHORT,
                    tp.nwSrc, tp.nwDst, tp.nwDst, tp.tpDst, tp.proto});
        }

        // TODO(pino): deal with case that conn couldn't be allocated.
        match.setNetworkDestination(conn.nwAddr);
        if (tp.proto != ICMP.PROTOCOL_NUMBER) {
            match.setTransportDestination(conn.tpPort);
        }
        res.action = action;

        fwdInfo.addFlowRemovedCallback(makeUnrefCallback(natMapping,
                                                         conn.unrefKey));
    }
}
