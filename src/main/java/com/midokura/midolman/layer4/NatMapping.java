package com.midokura.midolman.layer4;

import java.util.Set;

import org.openflow.protocol.OFMatch;

import com.midokura.midolman.openflow.MidoMatch;
import com.midokura.midolman.rules.NatTarget;

public interface NatMapping {

    // Allocates and records a Dnat mapping.
    NwTpPair allocateDnat(int nwSrc, short tpSrc, int oldNwDst, short oldTpDst,
            Set<NatTarget> nats, MidoMatch origMatch);

    NwTpPair lookupDnatFwd(
            int nwSrc, short tpSrc, int oldNwDst, short oldTpDst);

    NwTpPair lookupDnatRev(
            int nwSrc, short tpSrc, int newNwDst, short newTpDst);

    // Allocates and records a Snat mapping.
    NwTpPair allocateSnat(int oldNwSrc, short oldTpSrc, int nwDst, short tpDst,
            Set<NatTarget> nats, MidoMatch origMatch);

    NwTpPair lookupSnatFwd(
            int oldNwSrc, short oldTpSrc, int nwDst, short tpDst);

    NwTpPair lookupSnatRev(
            int newNwSrc, short newTpSrc, int nwDst, short tpDst);

    // The implementation of this method should reserve and clean up resources.
    void updateSnatTargets(Set<NatTarget> targets);

    void onFlowRemoved(OFMatch match);
}
