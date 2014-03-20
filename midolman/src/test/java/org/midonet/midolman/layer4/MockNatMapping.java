/*
 * Copyright 2011 Midokura KK
 */

package org.midonet.midolman.layer4;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import org.midonet.midolman.rules.NatTarget;
import org.midonet.packets.IPAddr;
import org.midonet.packets.IPv4Addr;


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
    public NwTpPair allocateDnat(byte protocol,
                                 IPAddr nwSrc, int tpSrc, IPAddr oldNwDst,
                                 int oldTpDst, Set<NatTarget> nats) {
        return allocateDnat(protocol, nwSrc, tpSrc, oldNwDst, oldTpDst,
                            nats, 0);
    }

    @Override
    public NwTpPair allocateDnat(byte protocol,
                                 IPAddr nwSrc, int tpSrc, IPAddr oldNwDst,
                                 int oldTpDst, Set<NatTarget> nats,
                                 int expirationSeconds) {
        // In this mock, just use the first nat target.
        NatTarget nat = nats.iterator().next();
        // TODO (ipv6) no idea why we need that cast
        IPAddr newNwDst = (IPv4Addr)nat.nwStart.randomTo(nat.nwEnd, rand);
        int newTpDst = rand.nextInt(nat.tpEnd - nat.tpStart + 1) + nat.tpStart;
        NwTpPair newDst = new NwTpPair(newNwDst, newTpDst);
        dnatFwdMap.put(new PacketSignature(protocol, nwSrc, tpSrc,
                                           oldNwDst, oldTpDst), newDst);
        dnatRevMap.put(new PacketSignature(protocol, nwSrc, tpSrc,
                                           newNwDst, newTpDst),
                       new NwTpPair(oldNwDst, oldTpDst));
        return newDst;
    }

    @Override
    public NwTpPair lookupDnatFwd(byte protocol, IPAddr nwSrc, int tpSrc,
                                  IPAddr oldNwDst, int oldTpDst) {
        return dnatFwdMap.get(new PacketSignature(
                                protocol, nwSrc, tpSrc, oldNwDst, oldTpDst));
    }

    @Override
    public NwTpPair lookupDnatFwd(byte protocol, IPAddr nwSrc, int tpSrc,
                                  IPAddr oldNwDst, int oldTpDst,
                                  int expirationSeconds) {
        return dnatFwdMap.get(new PacketSignature(
                protocol, nwSrc, tpSrc, oldNwDst, oldTpDst));
    }

    @Override
    public NwTpPair lookupDnatRev(byte protocol, IPAddr nwSrc, int tpSrc,
                                  IPAddr newNwDst, int newTpDst) {
        return dnatRevMap.get(new PacketSignature(
                                protocol, nwSrc, tpSrc, newNwDst, newTpDst));
    }

    @Override
    public void deleteDnatEntry(byte protocol,
                         IPAddr nwSrc, int tpSrc,
                         IPAddr oldNwDst, int oldTpDst,
                         IPAddr newNwDst, int newTpDst) {
        PacketSignature fwdSig = new PacketSignature(
                protocol, nwSrc, tpSrc, oldNwDst, oldTpDst);
        PacketSignature revSig = new PacketSignature(
                protocol, nwSrc, tpSrc, newNwDst, newTpDst);
        dnatFwdMap.remove(fwdSig);
        dnatRevMap.remove(revSig);

    }

    @Override
    public NwTpPair allocateSnat(byte protocol, IPAddr oldNwSrc, int oldTpSrc,
                                 IPAddr nwDst, int tpDst, Set<NatTarget> nats) {
        // In this mock, just use the first nat target.
        NatTarget nat = nats.iterator().next();
        // TODO (ipv6) no idea why we need that cast
        IPAddr newNwSrc = (IPv4Addr)nat.nwStart.randomTo(nat.nwEnd, rand);
        int newTpSrc = rand.nextInt(nat.tpEnd - nat.tpStart + 1) + nat.tpStart;
        NwTpPair newSrc = new NwTpPair(newNwSrc, newTpSrc);
        snatFwdMap.put(new PacketSignature(
            protocol, oldNwSrc, oldTpSrc, nwDst, tpDst), newSrc);
        snatRevMap.put(new PacketSignature(
                                protocol, newNwSrc, newTpSrc, nwDst, tpDst),
                       new NwTpPair(oldNwSrc, oldTpSrc));
        return newSrc;
    }

    @Override
    public NwTpPair lookupSnatFwd(byte protocol, IPAddr oldNwSrc, int oldTpSrc,
                                  IPAddr nwDst, int tpDst) {
        return snatFwdMap.get(new PacketSignature(
                                protocol, oldNwSrc, oldTpSrc, nwDst, tpDst));
    }

    @Override
    public NwTpPair lookupSnatRev(byte protocol, IPAddr newNwSrc, int newTpSrc,
                                  IPAddr nwDst, int tpDst) {
        return snatRevMap.get(new PacketSignature(
                                protocol, newNwSrc, newTpSrc, nwDst, tpDst));
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
