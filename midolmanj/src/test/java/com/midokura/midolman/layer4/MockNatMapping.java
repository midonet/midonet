/*
 * Copyright 2011 Midokura KK
 */

package com.midokura.midolman.layer4;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import com.midokura.midolman.rules.NatTarget;


public class MockNatMapping implements NatMapping {

    Map<PacketSignature, NwTpPair> dnatFwdMap;
    Map<PacketSignature, NwTpPair> dnatRevMap;
    Map<PacketSignature, NwTpPair> snatFwdMap;
    Map<PacketSignature, NwTpPair> snatRevMap;
    Random rand;
    public Set<NatTarget> snatTargets;
    public Set<NatTarget> recentlyFreedSnatTargets;

    public MockNatMapping() {
        dnatFwdMap = new HashMap<PacketSignature, NwTpPair>();
        dnatRevMap = new HashMap<PacketSignature, NwTpPair>();
        snatFwdMap = new HashMap<PacketSignature, NwTpPair>();
        snatRevMap = new HashMap<PacketSignature, NwTpPair>();
        rand = new Random();
        snatTargets = null;
        recentlyFreedSnatTargets = null;
    }

    @Override
    public NwTpPair allocateDnat(int nwSrc, short tpSrc, int oldNwDst,
            short oldTpDst, Set<NatTarget> nats) {
        // In this mock, just use the first nat target.
        NatTarget nat = nats.iterator().next();
        int newNwDst = rand.nextInt(nat.nwEnd - nat.nwStart + 1) + nat.nwStart;
        short newTpDst = (short) (rand.nextInt(nat.tpEnd - nat.tpStart + 1) + nat.tpStart);
        NwTpPair newDst = new NwTpPair(newNwDst, newTpDst);
        dnatFwdMap.put(new PacketSignature(nwSrc, tpSrc, oldNwDst, oldTpDst),
                newDst);
        dnatRevMap.put(new PacketSignature(nwSrc, tpSrc, newNwDst, newTpDst),
                new NwTpPair(oldNwDst, oldTpDst));
        return newDst;
    }

    @Override
    public NwTpPair lookupDnatFwd(int nwSrc, short tpSrc, int oldNwDst,
            short oldTpDst) {
        return dnatFwdMap.get(new PacketSignature(nwSrc, tpSrc, oldNwDst,
                oldTpDst));
    }

    @Override
    public NwTpPair lookupDnatRev(int nwSrc, short tpSrc, int newNwDst,
            short newTpDst) {
        return dnatRevMap.get(new PacketSignature(nwSrc, tpSrc, newNwDst,
                newTpDst));
    }

    @Override
    public NwTpPair allocateSnat(int oldNwSrc, short oldTpSrc, int nwDst,
            short tpDst, Set<NatTarget> nats) {
        // In this mock, just use the first nat target.
        NatTarget nat = nats.iterator().next();
        int newNwSrc = rand.nextInt(nat.nwEnd - nat.nwStart + 1) + nat.nwStart;
        short newTpSrc = (short) (rand.nextInt(nat.tpEnd - nat.tpStart + 1) + nat.tpStart);
        NwTpPair newSrc = new NwTpPair(newNwSrc, newTpSrc);
        snatFwdMap.put(new PacketSignature(oldNwSrc, oldTpSrc, nwDst, tpDst),
                newSrc);
        snatRevMap.put(new PacketSignature(newNwSrc, newTpSrc, nwDst, tpDst),
                new NwTpPair(oldNwSrc, oldTpSrc));
        return newSrc;
    }

    @Override
    public NwTpPair lookupSnatFwd(int oldNwSrc, short oldTpSrc, int nwDst,
            short tpDst) {
        return snatFwdMap.get(new PacketSignature(oldNwSrc, oldTpSrc, nwDst,
                tpDst));
    }

    @Override
    public NwTpPair lookupSnatRev(int newNwSrc, short newTpSrc, int nwDst,
            short tpDst) {
        return snatRevMap.get(new PacketSignature(newNwSrc, newTpSrc, nwDst,
                tpDst));
    }

    @Override
    public void updateSnatTargets(Set<NatTarget> targets) {
        // This method simulates the release of resources for Snat targets
        // that are no longer used and the reservation of resources for current
        // targets.
        recentlyFreedSnatTargets = snatTargets;
        if (null != recentlyFreedSnatTargets)
            recentlyFreedSnatTargets.removeAll(targets);
        snatTargets = targets;
    }

    @Override
    public void natUnref(String fwdKey) {
        // Do nothing.
    }
}
