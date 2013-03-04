/*
 * Copyright 2011 Midokura KK
 */

package org.midonet.midolman.layer4;

import java.util.Set;

import org.midonet.midolman.rules.NatTarget;

public interface NatMapping {

    // Allocates and records a Dnat mapping.
    NwTpPair allocateDnat(byte protocol,
                          int nwSrc, int tpSrc,
                          int oldNwDst, int oldTpDst, Set<NatTarget> nats);

    NwTpPair lookupDnatFwd(byte protocol,
                           int nwSrc, int tpSrc,
                           int oldNwDst, int oldTpDst);

    NwTpPair lookupDnatRev(byte protocol,
                           int nwSrc, int tpSrc,
                           int newNwDst, int newTpDst);

    // Allocates and records a Snat mapping.
    NwTpPair allocateSnat(byte protocol,
                          int oldNwSrc, int oldTpSrc,
                          int nwDst, int tpDst, Set<NatTarget> nats);

    NwTpPair lookupSnatFwd(byte protocol,
                           int oldNwSrc, int oldTpSrc,
                           int nwDst, int tpDst);

    NwTpPair lookupSnatRev(byte protocol,
                           int newNwSrc, int newTpSrc,
                           int nwDst, int tpDst);

    // The implementation of this method should reserve and clean up resources.
    void updateSnatTargets(Set<NatTarget> targets);

    void natUnref(String fwdKey);

}
