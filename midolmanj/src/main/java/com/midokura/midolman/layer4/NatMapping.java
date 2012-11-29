/*
 * Copyright 2011 Midokura KK
 */

package com.midokura.midolman.layer4;

import java.util.Set;

import com.midokura.midolman.rules.NatTarget;


public interface NatMapping {

    // Allocates and records a Dnat mapping.
    NwTpPair allocateDnat(int nwSrc, int tpSrc, int oldNwDst, int oldTpDst,
            Set<NatTarget> nats);

    NwTpPair lookupDnatFwd(int nwSrc, int tpSrc, int oldNwDst,
            int oldTpDst);

    NwTpPair lookupDnatRev(
            int nwSrc, int tpSrc, int newNwDst, int newTpDst);

    // Allocates and records a Snat mapping.
    NwTpPair allocateSnat(int oldNwSrc, int oldTpSrc, int nwDst, int tpDst,
            Set<NatTarget> nats);

    NwTpPair lookupSnatFwd(int oldNwSrc, int oldTpSrc, int nwDst, int tpDst);

    NwTpPair lookupSnatRev(
            int newNwSrc, int newTpSrc, int nwDst, int tpDst);

    // The implementation of this method should reserve and clean up resources.
    void updateSnatTargets(Set<NatTarget> targets);

    void natUnref(String fwdKey);
}
