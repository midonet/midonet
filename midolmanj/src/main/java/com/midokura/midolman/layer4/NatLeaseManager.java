/*
 * Copyright 2011 Midokura KK
 */

package com.midokura.midolman.layer4;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.Iterator;
import java.util.NavigableSet;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.cache.Cache;
import com.midokura.midolman.rules.NatTarget;
import com.midokura.midolman.state.zkManagers.FiltersZkManager;
import com.midokura.midolman.util.Net;
import com.midokura.packets.IPv4;
import com.midokura.util.eventloop.Reactor;


public class NatLeaseManager implements NatMapping {

    private static final Logger log =
            LoggerFactory.getLogger(NatLeaseManager.class);
    private static final int USHORT = 0xffff;

    public static final String FWD_DNAT_PREFIX = "dnatfwd";
    public static final String REV_DNAT_PREFIX = "dnatrev";
    public static final String FWD_SNAT_PREFIX = "snatfwd";
    public static final String REV_SNAT_PREFIX = "snatrev";

    // The following maps IP addresses to ordered lists of free ports.
    // These structures are meant to be shared by all rules/snat targets.
    // So snat targets for different rules can overlap and we'll still avoid
    // collisions. That's why we don't care about the snat target here.
    // Note that we use a NavigableSet instead of a simple list because different
    // snat targets might use different port ranges for the same ip.
    // Also note that we don't care about ip ranges - snat targets with more than
    // one ip in their range get broken up into separate entries here.
    // This map should be cleared if we lose our connection to ZK.
    ConcurrentMap<Integer, NavigableSet<Integer>> ipToFreePortsMap;
    private FiltersZkManager filterMgr;
    private UUID routerId;
    private String rtrIdStr;
    private Cache cache;
    private Reactor reactor;
    private Random rand;
    private int refreshSeconds;
    private ConcurrentMap<String, KeyMetadata> fwdKeys;

    private class KeyMetadata {
        public String revKey;
        public ScheduledFuture future;
        public AtomicInteger flowCount;

        public KeyMetadata(String revKey) {
            flowCount = new AtomicInteger(1);
            this.revKey = revKey;
            future = null;
        }
    }

    public NatLeaseManager(FiltersZkManager filterMgr, UUID routerId,
            Cache cache, Reactor reactor) {
        log.debug("constructor with {}, {}, {}, and {}",
            new Object[] {filterMgr, routerId, cache, reactor});
        this.filterMgr = filterMgr;
        this.ipToFreePortsMap = new ConcurrentHashMap<Integer, NavigableSet<Integer>>();
        this.routerId = routerId;
        rtrIdStr = routerId.toString();
        this.cache = cache;
        this.refreshSeconds = cache.getExpirationSeconds() / 2;
        this.reactor = reactor;
        this.rand = new Random();
        this.fwdKeys = new ConcurrentHashMap<String, KeyMetadata>();
    }

    private class RefreshNatMappings implements Runnable {
        String fwdKey;

        private RefreshNatMappings(String fwdKey) {
            this.fwdKey = fwdKey;
        }

        private void refreshKey(String key) {
            if (null == key) {
                log.error("SCREAM: refreshKey() got a null key");
                return;
            }
            log.debug("RefreshNatMappings refresh key {}", key);
            try {
                String val = cache.getAndTouch(key);
                log.debug("RefreshNatMappings found value {}", val);
            } catch (Exception e) {
                log.error("RefreshNatMappings caught: {}", e);
            }
        }

        @Override
        public void run() {
            log.debug("RefreshNatMappings for fwd key {}", fwdKey);
            KeyMetadata keyData = fwdKeys.get(fwdKey);
            if (null == keyData) {
                // The key's flows must have expired, stop refreshing.
                log.debug("RefreshNatMappings stop refresh, got null metadata.");
                return;
            }
            // Refresh both nat keys
            refreshKey(fwdKey);
            refreshKey(keyData.revKey);
            log.debug("RefreshNatMappings completed. Rescheduling.");
            // Re-schedule this runnable.
            reactor.schedule(this, refreshSeconds, TimeUnit.SECONDS);
        }
    }

    @Override
    public NwTpPair allocateDnat(int nwSrc, short tpSrc_, int oldNwDst,
                                 short oldTpDst_, Set<NatTarget> nats) {
        // TODO(pino) get rid of these after converting ports to int.
        int tpSrc = tpSrc_ & USHORT;
        int oldTpDst = oldTpDst_ & USHORT;
        log.debug("allocateDnat: nwSrc {} tpSrc {} oldNwDst {} oldTpDst {} "
                + "nats {}", new Object[] { IPv4.fromIPv4Address(nwSrc),
                tpSrc, IPv4.fromIPv4Address(oldNwDst), oldTpDst, nats });

        if (nats.size() == 0)
            throw new IllegalArgumentException("Nat list was emtpy.");
        int natPos = rand.nextInt(nats.size());
        Iterator<NatTarget> iter = nats.iterator();
        NatTarget nat = null;
        for (int i = 0; i <= natPos && iter.hasNext(); i++)
            nat = iter.next();
        int tpStart = nat.tpStart & USHORT;
        int tpEnd = nat.tpEnd & USHORT;
        int newNwDst = rand.nextInt(nat.nwEnd - nat.nwStart + 1) + nat.nwStart;
        int newTpDst = rand.nextInt(tpEnd - tpStart + 1) + tpStart;
        log.debug("{} DNAT allocated new DST {}:{} to flow from {}:{} to "
                + "{}:{}",
                new Object[] { rtrIdStr, IPv4.fromIPv4Address(newNwDst),
                        newTpDst, IPv4.fromIPv4Address(nwSrc),
                        tpSrc, IPv4.fromIPv4Address(oldNwDst),
                        oldTpDst });

        // TODO(pino): can't one write to the cache suffice?
        String fwdKey = makeCacheKey(FWD_DNAT_PREFIX, nwSrc, tpSrc, oldNwDst,
                oldTpDst);
        cache.set(fwdKey, makeCacheValue(newNwDst, newTpDst));
        String revKey = makeCacheKey(REV_DNAT_PREFIX, nwSrc, tpSrc, newNwDst,
                                     newTpDst);
        cache.set(revKey, makeCacheValue(oldNwDst, oldTpDst));
        log.debug("allocateDnat fwd key {} and rev key {}", fwdKey, revKey);
        scheduleRefresh(fwdKey, revKey);
        return new NwTpPair(newNwDst, (short)newTpDst, fwdKey);
    }

    private void scheduleRefresh(String fwdKey, String revKey) {
        boolean isNew = natRef(fwdKey, revKey);
        KeyMetadata keyData = fwdKeys.get(fwdKey);
        if (null == keyData) {
            log.error("SCREAM: key not found after natRef()");
            return;
        }

        if (isNew) {
            keyData.future = reactor.schedule(
                    new RefreshNatMappings(fwdKey),
                    refreshSeconds,
                    TimeUnit.SECONDS);
            log.debug("scheduleRefresh");
        }
    }

    public static String makeCacheKey(String prefix, int nwSrc, int tpSrc,
                                                     int nwDst, int tpDst) {
        return String.format("%s%08x:%d:%08x:%d", prefix, nwSrc,
                tpSrc & USHORT, nwDst, tpDst & USHORT);
    }

    public static String makeCacheValue(int nwAddr, int tpPort) {
        return String.format("%08x/%d", nwAddr, tpPort & USHORT);
    }

    private static NwTpPair makePairFromString(String value, String unrefKey) {
        if (null == value || value.equals(""))
            return null;
        String[] parts = value.split("/");
        try {
            return new NwTpPair((int) Long.parseLong(parts[0], 16),
                    (short) Integer.parseInt(parts[1]),
                    unrefKey);
        }
        catch (Exception e) {
            log.warn("makePairFromString bad value {}", value);
            return null;
        }
    }

    @Override
    public NwTpPair lookupDnatFwd(int nwSrc, short tpSrc_,
                                  int oldNwDst, short oldTpDst_) {
        int tpSrc = tpSrc_ & USHORT;
        int oldTpDst = oldTpDst_ & USHORT;
        String fwdKey = makeCacheKey(FWD_DNAT_PREFIX, nwSrc, tpSrc,
                oldNwDst, oldTpDst);
        String value = cache.getAndTouch(fwdKey);
        log.debug("lookupDnatFwd: key {} value {}", fwdKey, value);
        // If the forward mapping was found, touch the reverse mapping too,
        // then schedule a refresh.
        if (null == value)
            return null;
        NwTpPair pair = makePairFromString(value, fwdKey);
        if (null == pair)
            return null;
        String revKey = makeCacheKey(REV_DNAT_PREFIX, nwSrc, tpSrc,
                pair.nwAddr, pair.tpPort);
        cache.getAndTouch(revKey);
        scheduleRefresh(fwdKey, revKey);
        return pair;
    }

    @Override
    public NwTpPair lookupDnatRev(int nwSrc, short tpSrc_,
                                  int newNwDst, short newTpDst_) {
        int tpSrc = tpSrc_ & USHORT;
        int newTpDst = newTpDst_ & USHORT;
        String key = makeCacheKey(REV_DNAT_PREFIX, nwSrc, tpSrc,
                newNwDst, newTpDst);
        String value = cache.get(key);
        log.debug("lookupDnatFwd key {} value {}", key, value);
        if (null == value)
            return null;
        return makePairFromString(value, null);
    }

    private NwTpPair makeSnatReservation(int oldNwSrc, int oldTpSrc,
            int newNwSrc, int newTpSrc, int nwDst, int tpDst) {
        log.debug("makeSnatReservation: oldNwSrc {} oldTpSrc {} newNwSrc {} "
                + "newTpSrc {} nw Dst {} tpDst {}",
                new Object[] { IPv4.fromIPv4Address(oldNwSrc),
                        oldTpSrc, IPv4.fromIPv4Address(newNwSrc),
                        newTpSrc, IPv4.fromIPv4Address(nwDst),
                        tpDst });

        String reverseKey = makeCacheKey(REV_SNAT_PREFIX, newNwSrc, newTpSrc,
                nwDst, tpDst);
        if (null != cache.get(reverseKey)) {
            log.warn("{} Snat encountered a collision reserving SRC {}:{}",
                    new Object[] { rtrIdStr, IPv4.fromIPv4Address(newNwSrc),
                            newTpSrc });
            return null;
        }
        // If we got here, we can use this port.
        log.debug("{} SNAT reserved new SRC {}:{} for flow from {}:{} to "
                + "{}:{}",
                new Object[] { rtrIdStr, IPv4.fromIPv4Address(newNwSrc),
                        newTpSrc, IPv4.fromIPv4Address(oldNwSrc),
                        oldTpSrc, IPv4.fromIPv4Address(nwDst),
                        tpDst });
        String fwdKey = makeCacheKey(FWD_SNAT_PREFIX, oldNwSrc, oldTpSrc, nwDst,
                tpDst);
        // TODO(pino): can't one write to the cache suffice?
        cache.set(fwdKey, makeCacheValue(newNwSrc, newTpSrc));
        cache.set(reverseKey, makeCacheValue(oldNwSrc, oldTpSrc));
        log.debug("allocateSnat fwd key {} and rev key {}", fwdKey, reverseKey);
        scheduleRefresh(fwdKey, reverseKey);
        return new NwTpPair(newNwSrc, (short)newTpSrc, fwdKey);
    }

    @Override
    public NwTpPair allocateSnat(int oldNwSrc, short oldTpSrc_, int nwDst,
                                 short tpDst_, Set<NatTarget> nats) {
        int oldTpSrc = oldTpSrc_ & USHORT;
        int tpDst = tpDst_ & USHORT;
        // First try to find a port in a block we've already leased.
        int numTries = 0;
        for (NatTarget tg : nats) {
            int tpStart = tg.tpStart & USHORT;
            int tpEnd = tg.tpEnd & USHORT;
            for (int ip = tg.nwStart; ip <= tg.nwEnd; ip++) {
                NavigableSet<Integer> freePorts = ipToFreePortsMap.get(ip);
                if (null == freePorts)
                    continue;
                while (true) {
                    synchronized (freePorts) {
                        Integer port = freePorts.ceiling(tpStart);
                        if (null == port || port > tpEnd)
                            break;
                        // Look for a port in the desired range
                        // We've found a free port.
                        freePorts.remove(port);
                        // Check cache to make sure the port's really free.
                        NwTpPair reservation = makeSnatReservation(
                                    oldNwSrc, oldTpSrc, ip, port, nwDst, tpDst);
                        if (reservation != null)
                            return reservation;
                    }
                    // Give up after 20 attempts.
                    numTries++;
                    if (numTries > 20) {
                        log.warn("allocateSnat failed to reserve 20 free "
                                + "ports. Giving up.");
                        return null;
                    }
                } // No free ports for this ip and port range
            } // No free ports for this NatTarget
        } // No free ports for any of the given NatTargets

        // None of our leased blocks were suitable. Try leasing another block.
        // TODO: Do something smarter. See:
        // https://sites.google.com/a/midokura.jp/wiki/midonet/srcnat-block-reservations
        int block_size = 100; // TODO: make this configurable?
        int numExceptions = 0;
        for (NatTarget tg : nats) {
            int tpStart = tg.tpStart & USHORT;
            int tpEnd = tg.tpEnd & USHORT;
            for (int ip = tg.nwStart; ip <= tg.nwEnd; ip++) {
                NavigableSet<Integer> reservedBlocks;
                try {
                    reservedBlocks = filterMgr.getSnatBlocks(routerId, ip);
                } catch (Exception e) {
                    log.error("allocateSnat got an exception listing reserved "
                            + "blocks:", e);
                    return null;
                }
                // Note that Shorts in this sorted set should only be
                // multiples of 100 because that's how we avoid
                // collisions/re-leasing. A Short s represents a lease on
                // the port range [s, s+99] inclusive.
                // Round down tpStart to the nearest 100.
                int block = (tpStart / block_size) * block_size;
                Iterator<Integer> iter = reservedBlocks.tailSet(block, true)
                        .iterator();
                // Find the first lowPort + 100*x that isn't in the tail-set
                // and is less than tpEnd
                while (iter.hasNext()) {
                    // Find the next reserved block.
                    Integer lease = iter.next();
                    if (lease > block) {
                        // No one reserved the current value of startBlock.
                        // Let's lease it ourselves.
                        break;
                    }
                    if (lease < block) {
                        // this should never happen. someone leased a
                        // block that doesn't start at a multiple of 100
                        continue;
                    }
                    // The normal case. The block is already leased, try
                    // the next one.
                    block += block_size;
                    if (block > tpEnd)
                        break;
                }
                if (block > tpEnd)
                    // No free blocks for this ip. Try the next ip.
                    break;
                try {
                    log.debug("allocateSnat trying to reserve snat block {} "
                            + "in ip {}", block,
                            Net.convertIntAddressToString(ip));
                    filterMgr.addSnatReservation(routerId, ip, block);
                } catch (Exception e) {
                    log.debug("allocateSnat block reservation failed.");
                    numExceptions++;
                    if (numExceptions > 1){
                        log.warn("allocateSnat failed twice to reserve a port "
                                + "block in ip {}. Giving up.",
                                Net.convertIntAddressToString(ip));
                        return null;
                    }
                    continue;
                }
                // Expand the port block.
                NavigableSet<Integer> freePorts = ipToFreePortsMap.get(ip);
                if (null == freePorts) {
                    freePorts = new TreeSet<Integer>();
                    NavigableSet<Integer> oldV = null;
                    oldV = ipToFreePortsMap.putIfAbsent(ip, freePorts);
                    if (null != oldV)
                        freePorts = oldV;
                }

                synchronized (freePorts) {
                    log.debug("allocateSnat adding range {} to {} to list of "
                            + "free ports.", block, block+block_size-1);
                    for (int i = 0; i < block_size; i++)
                        freePorts.add(block + i);
                    // Now, starting with the smaller of 'block' and tpStart
                    // see if the mapping really is free in cache by making sure
                    // that the reverse mapping isn't already taken. Note that
                    // the common case for snat requires 4 calls to cache (one
                    // to check whether we've already seen the forward flow, one
                    // to make sure the newIp, newPort haven't already been used
                    // with the nwDst and tpDst, and 2 to actually store the
                    // forward and reverse mappings).
                    int freePort = block;
                    if (freePort < tpStart)
                        freePort = tpStart;
                    while (true) {
                        freePorts.remove(freePort);
                        NwTpPair reservation = makeSnatReservation(
                                oldNwSrc, oldTpSrc, ip, freePort, nwDst, tpDst);
                        if (reservation != null)
                            return new NwTpPair(ip, (short)freePort);
                        freePort++;
                        if (0 == freePort % block_size || freePort > tpEnd) {
                            log.warn("allocateSnat unable to reserve any port "
                                    + "in the newly reserved block. Giving up.");
                            return null;
                        }
                    }
                }
            } // End for loop over ip addresses in a nat target.
        } // End for loop over nat targets.
        return null;
    }

    @Override
    public NwTpPair lookupSnatFwd(int oldNwSrc, short oldTpSrc_,
                                  int nwDst, short tpDst_) {
        int oldTpSrc = oldTpSrc_ & USHORT;
        int tpDst = tpDst_ & USHORT;
        String fwdKey = makeCacheKey(FWD_SNAT_PREFIX, oldNwSrc, oldTpSrc,
                nwDst, tpDst);
        String value = cache.getAndTouch(fwdKey);
        log.debug("lookupSnatFwd: key {} value {}", fwdKey, value);
        if (null == value)
            return null;
        NwTpPair pair = makePairFromString(value, fwdKey);
        if (null == pair)
            return null;
        // If the forward mapping was found, touch the reverse mapping too,
        // then schedule a refresh.
        String revKey = makeCacheKey(REV_SNAT_PREFIX, pair.nwAddr,
                pair.tpPort, nwDst, tpDst);
        cache.getAndTouch(revKey);
        scheduleRefresh(fwdKey, revKey);
        return pair;
    }

    @Override
    public NwTpPair lookupSnatRev(int newNwSrc, short newTpSrc_,
                                  int nwDst, short tpDst_) {
        int newTpSrc = newTpSrc_ & USHORT;
        int tpDst = tpDst_ & USHORT;
        String key = makeCacheKey(REV_SNAT_PREFIX, newNwSrc, newTpSrc,
                nwDst, tpDst);
        String value = cache.get(key);
        log.debug("lookupSnatRev: key {} value {}", key, value);
        if (null == value)
            return null;
        return makePairFromString(value, null);
    }

    @Override
    public void updateSnatTargets(Set<NatTarget> targets) {
        log.warn("updateSnatTargets: {}", targets);

        // TODO Auto-generated method stub

    }

    @Override
    public void natUnref(String fwdKey) {
        log.debug("natUnref: key {}", fwdKey);
        KeyMetadata keyData = fwdKeys.get(fwdKey);
        if (null == keyData || keyData.flowCount.decrementAndGet() > 0) {
            // this was not the last user of this key
            return;
        }

        // Cancel refreshing of the nat keys
        log.debug("natUnref canceling refresh of key {}", fwdKey);
        if (null != keyData.revKey) {
            log.debug("natUnref canceling refresh of key {}",
                      keyData.revKey);
        }
        if (null != keyData.future) {
            log.debug("natUnref found future to cancel.");
            keyData.future.cancel(false);
        }
    }

    private boolean natRef(String fwdKey, String revKey) {
        log.debug("incrementing reference count for key {}", fwdKey);
        KeyMetadata keyData = fwdKeys.get(fwdKey);
        if (null == keyData) {
            /* This is a new key. Put a new metadata object into the map.
             * If somebody else races with us and adds it first, start over with
             * a recursive call.
             */
            keyData = new KeyMetadata(revKey);
            KeyMetadata oldV = fwdKeys.putIfAbsent(fwdKey, keyData);
            return (null == oldV) ? true : natRef(fwdKey, revKey);
        } else {
            /* This is a known key. If somebody raced with us to delete the
             * key while we increment the refcount, start over with a recursive
             * call.
             */
            if (keyData.flowCount.incrementAndGet() <= 1) {
                keyData.flowCount.decrementAndGet();
                return natRef(fwdKey, revKey);
            }

            return (keyData == fwdKeys.get(fwdKey)) ?
                    false :
                    natRef(fwdKey, revKey);
        }
    }
}
