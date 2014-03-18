/*
 * Copyright 2011 Midokura KK
 */

package org.midonet.midolman.layer4;

import java.util.Set;

import org.midonet.midolman.rules.NatTarget;
import org.midonet.packets.IPAddr;


public interface NatMapping {

    // Allocates and records a Dnat mapping.
    NwTpPair allocateDnat(byte protocol,
                          IPAddr nwSrc, int tpSrc,
                          IPAddr oldNwDst, int oldTpDst, Set<NatTarget> nats);

    NwTpPair allocateDnat(byte protocol,
                          IPAddr nwSrc, int tpSrc,
                          IPAddr oldNwDst, int oldTpDst, Set<NatTarget> nats,
                          int cacheTimeoutSeconds);

    NwTpPair lookupDnatFwd(byte protocol,
                           IPAddr nwSrc, int tpSrc,
                           IPAddr oldNwDst, int oldTpDst);

    NwTpPair lookupDnatFwd(byte protocol,
                           IPAddr nwSrc, int tpSrc,
                           IPAddr oldNwDst, int oldTpDst,
                           int cacheTimeoutSeconds);

    NwTpPair lookupDnatRev(byte protocol,
                           IPAddr nwSrc, int tpSrc,
                           IPAddr newNwDst, int newTpDst);

    void deleteDnatEntry(byte protocol,
                           IPAddr nwSrc, int tpSrc,
                           IPAddr oldNwDst, int oldTpDst,
                           IPAddr newNwDst, int newTpDst);

    // Allocates and records a Snat mapping.
    NwTpPair allocateSnat(byte protocol,
                          IPAddr oldNwSrc, int oldTpSrc,
                          IPAddr nwDst, int tpDst, Set<NatTarget> nats);

    NwTpPair lookupSnatFwd(byte protocol,
                           IPAddr oldNwSrc, int oldTpSrc,
                           IPAddr nwDst, int tpDst);

    NwTpPair lookupSnatRev(byte protocol,
                           IPAddr newNwSrc, int newTpSrc,
                           IPAddr nwDst, int tpDst);

    // The implementation of this method should reserve and clean up resources.
    void updateSnatTargets(Set<NatTarget> targets);

    void natUnref(String fwdKey);

}
