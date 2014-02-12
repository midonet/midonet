/*
 * Copyright 2011 Midokura KK
 */

package org.midonet.midolman.layer4;

import java.util.Iterator;
import java.util.NavigableSet;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.midonet.cache.Cache;
import org.midonet.midolman.rules.NatTarget;
import org.midonet.midolman.state.zkManagers.FiltersZkManager;
import org.midonet.packets.ICMP;
import org.midonet.packets.IPAddr;
import org.midonet.packets.IPAddr$;
import org.midonet.packets.IPv4Addr;
import org.midonet.packets.IPv6Addr;
import org.midonet.util.eventloop.Reactor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public class NatLeaseManager implements NatMapping {

    private static final Logger log =
            LoggerFactory.getLogger(NatLeaseManager.class);
    private static final int USHORT = 0xffff;

    private static final int MAX_PORT_ALLOC_ATTEMPTS = 20;
    private static final int BLOCK_SIZE = 100;

    public static final String FWD_DNAT_PREFIX = "dnatfwd";
    public static final String REV_DNAT_PREFIX = "dnatrev";
    public static final String FWD_SNAT_PREFIX = "snatfwd";
    public static final String REV_SNAT_PREFIX = "snatrev";

    // The following maps IP addresses to ordered lists of free ports (for
    // non-ICMP protocols).
    //
    // These structures are meant to be shared by all rules/snat targets.
    // So snat targets for different rules can overlap and we'll still avoid
    // collisions. That's why we don't care about the snat target here.
    // In the case of ICMP, we don't take watch for collisions and instead
    // rely on randomness on the icmp identifiers (which are used as ports).
    //
    // Note that we use a NavigableSet instead of a simple list because different
    // snat targets might use different port ranges for the same ip.
    //
    // Also note that we don't care about ip ranges - snat targets with more than
    // one ip in their range get broken up into separate entries here.
    // This map should be cleared if we lose our connection to ZK.
    ConcurrentMap<IPAddr, NavigableSet<Integer>> ipToFreePortsMap;
    ConcurrentMap<String, KeyMetadata> fwdKeys;
    private FiltersZkManager filterMgr;
    private UUID routerId;
    private String rtrIdStr;
    private Cache cache;
    private Reactor reactor;
    private Random rand;
    private int refreshSeconds;

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
        this.ipToFreePortsMap = new ConcurrentHashMap<IPAddr, NavigableSet<Integer>>();
        this.routerId = routerId;
        this.rtrIdStr = routerId.toString();
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

    /**
     * This method offers unified DNAT allocation for any supported protocol
     * verified by isNatSupported. It will take a 4-tuple and try to create a
     * translated 4-tuple. Only source values will be translated.
     *
     * It's possible to use this method even with a protocol that doesn't have
     * source and destination transport, such as ICMP. In this case, "fake"
     * values can be set in tpSrc and tpDst fields.
     *
     * However, users are encouraged to provide values that minimize the
     * chance of collisions when performing lookups.
     *
     * For example, in ICMP ECHO messages this is achieved using the ICMP ECHO
     * identifier field as both tpSrc and tpDst, relying on the random
     * generation of these values as a sufficient prevention of collisions.
     *
     * @param protocol of the packet
     * @param nwSrc src ip, may get translated in the return pair
     * @param tpSrc_ src transport, may get translated in the return pair
     * @param oldNwDst old dst ip, will not get translated in the return pair
     * @param oldTpDst_ old dst transport, will not get translated in the return
     *                  pair
     * @param nats
     * @return
     */
    @Override
    public NwTpPair allocateDnat(byte protocol, IPAddr nwSrc, int tpSrc_,
                                 IPAddr oldNwDst, int oldTpDst_,
                                 Set<NatTarget> nats) {

        // TODO(pino) get rid of these after converting ports to int.
        int tpSrc = tpSrc_ & USHORT;
        int oldTpDst = oldTpDst_ & USHORT;
        log.debug("allocateDnat: proto {} nwSrc {} tpSrc {} oldNwDst {} " +
                  "oldTpDst {} nats {}", new Object[] { protocol,
                      nwSrc, tpSrc, oldNwDst, oldTpDst, nats });

        if (nats.isEmpty())
            throw new IllegalArgumentException("Nat list was emtpy.");
        int natPos = rand.nextInt(nats.size());
        Iterator<NatTarget> iter = nats.iterator();
        NatTarget nat = null;
        for (int i = 0; i <= natPos && iter.hasNext(); i++)
            nat = iter.next();

        int tpStart = nat.tpStart & USHORT;
        int tpEnd = nat.tpEnd & USHORT;
        // TODO (ipv6) no idea why we need that cast
        IPAddr newNwDst = (IPv4Addr)nat.nwStart.randomTo(nat.nwEnd, rand);

        // newNwDst needs to be the same type as nwSrc, nwDst
        int newTpDst = (protocol == ICMP.PROTOCOL_NUMBER)
                       ? oldTpDst
                       : rand.nextInt(tpEnd - tpStart + 1) + tpStart;
        log.debug("{} DNAT allocated new DST {}:{} to flow from {}:{} to "
                + "{}:{} protocol {}", new Object[] { rtrIdStr,
                        newNwDst, newTpDst, nwSrc, tpSrc, oldNwDst,
                        oldTpDst, protocol});

        // TODO(pino): can't one write to the cache suffice?
        String fwdKey = makeCacheKey(FWD_DNAT_PREFIX, protocol,
                                     nwSrc, tpSrc, oldNwDst, oldTpDst);
        cache.set(fwdKey, makeCacheValue(newNwDst, newTpDst));
        String revKey = makeCacheKey(REV_DNAT_PREFIX, protocol,
                                     nwSrc, tpSrc, newNwDst, newTpDst);
        cache.set(revKey, makeCacheValue(oldNwDst, oldTpDst));
        log.debug("allocateDnat fwd key {} and rev key {}", fwdKey, revKey);
        scheduleRefresh(fwdKey, revKey);
        return new NwTpPair(newNwDst, newTpDst, fwdKey);
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

    public static String makeCacheKey(String prefix, byte protocol,
                                      IPAddr nwSrc, int tpSrc, IPAddr nwDst,
                                      int tpDst) {
        return String.format("%s|%x|%s|%d|%s|%d", prefix, protocol,
                             nwSrc.toString(), tpSrc & USHORT, nwDst.toString(),
                             tpDst & USHORT);
    }

    public static String makeCacheValue(IPAddr nwAddr, int tpPort) {
        return String.format("%s/%d", nwAddr.toString(), tpPort & USHORT);
    }

    private static NwTpPair makePairFromString(String value, String unrefKey) {
        if (null == value || value.equals(""))
            return null;
        String[] parts = value.split("/");
        try {
            return new NwTpPair(IPAddr$.MODULE$.fromString(parts[0]),
                                Integer.parseInt(parts[1]), unrefKey);
        }
        catch (Exception e) {
            log.warn("makePairFromString bad value {}", value);
            return null;
        }
    }

    @Override
    public NwTpPair lookupDnatFwd(byte protocol, IPAddr nwSrc, int tpSrc_,
                                  IPAddr oldNwDst, int oldTpDst_) {
        int tpSrc = tpSrc_ & USHORT;
        int oldTpDst = oldTpDst_ & USHORT;
        String fwdKey = makeCacheKey(FWD_DNAT_PREFIX, protocol,
                                     nwSrc, tpSrc, oldNwDst, oldTpDst);
        String value = cache.getAndTouch(fwdKey);
        log.debug("lookupDnatFwd: key {} value {}", fwdKey, value);
        // If the forward mapping was found, touch the reverse mapping too,
        // then schedule a refresh.
        if (null == value)
            return null;
        NwTpPair pair = makePairFromString(value, fwdKey);
        if (null == pair)
            return null;
        String revKey = makeCacheKey(REV_DNAT_PREFIX, protocol,
                                     nwSrc, tpSrc, pair.nwAddr, pair.tpPort);
        cache.getAndTouch(revKey);
        scheduleRefresh(fwdKey, revKey);
        return pair;
    }

    @Override
    public NwTpPair lookupDnatRev(byte protocol, IPAddr nwSrc, int tpSrc_,
                                  IPAddr newNwDst, int newTpDst_) {
        int tpSrc = tpSrc_ & USHORT;
        int newTpDst = newTpDst_ & USHORT;
        String key = makeCacheKey(REV_DNAT_PREFIX, protocol,
                                  nwSrc, tpSrc, newNwDst, newTpDst);
        String value = cache.get(key);
        log.debug("lookupDnatRev key {} value {}", key, value);
        return (null == value) ? null : makePairFromString(value, null);
    }

    private NwTpPair makeSnatReservation(byte protocol,
                                         IPAddr oldNwSrc, int oldTpSrc,
                                         IPAddr newNwSrc, int newTpSrc,
                                         IPAddr nwDst, int tpDst) {
        log.debug("makeSnatReservation: protocol {} oldNwSrc {} oldTpSrc {} " +
                  "newNwSrc {} newTpSrc {} nw Dst {} tpDst {}",
                new Object[] { protocol, oldNwSrc, oldTpSrc, newNwSrc, newTpSrc,
                    nwDst, tpDst });

        String reverseKey = makeCacheKey(REV_SNAT_PREFIX, protocol,
                                         newNwSrc, newTpSrc, nwDst, tpDst);
        if (null != cache.get(reverseKey)) {
            log.warn(
                "{} Snat encountered a collision reserving SRC {}:{}, proto: {}",
                new Object[] { rtrIdStr, newNwSrc, newTpSrc, protocol });
            return null;
        }
        // If we got here, we can use this port.
        log.debug("{} SNAT reserved new SRC {}:{} for flow from {}:{} to "
                + "{}:{}, protocol {}",
                new Object[] { rtrIdStr, newNwSrc, newTpSrc, oldNwSrc,
                        oldTpSrc, nwDst, tpDst, protocol});
        String fwdKey = makeCacheKey(FWD_SNAT_PREFIX, protocol,
                                     oldNwSrc, oldTpSrc, nwDst, tpDst);
        // TODO(pino): can't one write to the cache suffice?
        cache.set(fwdKey, makeCacheValue(newNwSrc, newTpSrc));
        cache.set(reverseKey, makeCacheValue(oldNwSrc, oldTpSrc));
        log.debug("allocateSnat fwd key {} and rev key {}", fwdKey, reverseKey);
        scheduleRefresh(fwdKey, reverseKey);
        return new NwTpPair(newNwSrc, newTpSrc, fwdKey);
    }

    /**
     * This method offers unified SNAT allocation for any supported protocol
     * verified by isNatSupported. It will take a 4-tuple and try to create a
     * translated 4-tuple. Only source values will be translated.
     *
     * It's possible to use this method even with a protocol that doesn't have
     * source and destination transport, such as ICMP. In this case, "fake"
     * values can be set in tpSrc and tpDst fields.
     *
     * However, users are encouraged to provide values that minimize the
     * chance of collisions when performing lookups.
     *
     * For example, in ICMP ECHO messages this is achieved using the ICMP ECHO
     * identifier field as both tpSrc and tpDst, relying on the random
     * generation of these values as a sufficient prevention of collisions.
     *
     * NOTE: it may not make sense to use this method in certain types of
     * packets. For example, an ICMP error should not allocate a SNAT.
     *
     * NOTE: for ICMP the ports are assumed to be fake ports that will not be
     * translated.
     *
     * @param protocol of the packet
     * @param oldNwSrc old src ip, may get translated in the return pair
     * @param oldTpSrc_ old src transport, may get translated in the return pair
     * @param nwDst dst ip, will not get translated in the return pair
     * @param tpDst_ dst transport, will not get translated in the return pair
     * @param nats
     * @return
     */
    @Override
    public NwTpPair allocateSnat(byte protocol, IPAddr oldNwSrc, int oldTpSrc_,
                                 IPAddr nwDst, int tpDst_, Set<NatTarget> nats) {

        int oldTpSrc = oldTpSrc_ & USHORT;
        int tpDst = tpDst_ & USHORT;

        // ICMP reservations are much simpler: we grant the same port as reqd.
        // which in fact is the ICMP id (we rely on id generation randomness)
        if (protocol == ICMP.PROTOCOL_NUMBER) {
            return allocateSnatICMP(oldNwSrc, oldTpSrc, nwDst, tpDst, nats);
        }

        // First try to find a port in a block we've already leased.
        int numTries = 0;
        for (NatTarget tg : nats) {
            int tpStart = tg.tpStart & USHORT;
            int tpEnd = tg.tpEnd & USHORT;
            for (IPAddr ip: tg.nwStart.range(tg.nwEnd)) {
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
                                    protocol, oldNwSrc, oldTpSrc,
                                    ip, port, nwDst, tpDst);
                        if (reservation != null)
                            return reservation;
                    }
                    // Give up after 20 attempts.
                    numTries++;
                    if (numTries > MAX_PORT_ALLOC_ATTEMPTS) {
                        log.warn("allocateSnat failed to reserve {} free "
                                + "ports. Giving up.", MAX_PORT_ALLOC_ATTEMPTS);
                        return null;
                    }
                } // No free ports for this ip and port range
            } // No free ports for this NatTarget
        } // No free ports for any of the given NatTargets

        // None of our leased blocks were suitable. Try leasing another block.
        // TODO: Do something smarter. See:
        // https://sites.google.com/a/midokura.jp/wiki/midonet/srcnat-block-reservations
        int block_size = BLOCK_SIZE; // TODO: make this configurable?
        int blockReservationFailures = 0;
        for (NatTarget tg : nats) {
            int tpStart = tg.tpStart & USHORT;
            int tpEnd = tg.tpEnd & USHORT;
            for (IPAddr ip: tg.nwStart.range(tg.nwEnd)) {
                NavigableSet<Integer> reservedBlocks;
                try {
                    reservedBlocks = filterMgr.getSnatBlocks(routerId, ip);
                } catch (Exception e) {
                    log.error("allocateSnat got an exception listing reserved "
                              + "blocks", e);
                    return null;
                }
                // Note that Shorts in this sorted set should only be
                // multiples of BLOCK_SIZE because that's how we avoid
                // collisions/re-leasing. A Short s represents a lease on
                // the port range [s, s+99] inclusive.
                // Round down tpStart to the nearest BLOCK_SIZE.
                int block = (tpStart / block_size) * block_size;
                // Find the first lowPort + BLOCK_SIZE*x that isn't in the
                // tail-set and is less than tpEnd
                for (Integer lease: reservedBlocks.tailSet(block, true)) {
                    // Find the next reserved block.
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
                    // Normal case. The block is already leased, try next one.
                    block += block_size;
                    if (block > tpEnd)
                        break;
                }

                if (block > tpEnd) // No free blocks for this ip. Try next ip.
                    break;

                if (!reserveSnatBlock(routerId, ip, block)) {
                    blockReservationFailures++;
                    if (blockReservationFailures > 1){
                        log.warn("allocateSnat failed twice to reserve a " +
                                 "port block in ip {}. Giving up.", ip);
                        return null;
                    }
                    continue; // skip to the next block
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
                        NwTpPair reservation = makeSnatReservation(protocol,
                               oldNwSrc, oldTpSrc, ip, freePort, nwDst, tpDst);
                        if (reservation != null)
                            return reservation;
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

    /*
     * Tries to reserve the given SNAT block, with a single retry on the same
     * block in the event that it gets a failure. See MN-883 for motivation:
     * if two threads reserve the same block, we don't want to get a failure
     * on the loser since that one can reuse the reservation.
     */
    private boolean reserveSnatBlock(UUID routerId, IPAddr ip, int block) {
        int attempts = 2;
        while (attempts-- > 0) {
            try {
                log.debug("allocateSnat trying to reserve snat block {} in " +
                          "ip {}", block, ip);
                filterMgr.addSnatReservation(routerId, ip, block);
                return true;
            } catch (Exception e) {
                log.warn("allocateSnat block reservation failed, retry " +
                             "same block", e);
            }
        }
        return false;
    }

    /**
     * This performs the SNAT allocation for ICMP since the logic differs from
     * the TCP/UDP case. In particular: in ICMP we can save iterating over
     * NatTarget ports since ICMP NAT will not translate identifiers (which
     * is what the tpSrc and tpDst contain).
     */
    private NwTpPair allocateSnatICMP(IPAddr oldNwSrc, int oldTpSrc_,
                                      IPAddr nwDst, int tpDst_,
                                      Set<NatTarget> nats) {
        int oldTpSrc = oldTpSrc_ & USHORT;
        int tpDst = tpDst_ & USHORT;
        int numTries = 0;
        for (NatTarget tg : nats) {
            for (IPAddr ip: tg.nwStart.range(tg.nwEnd)) {
                NwTpPair reservation = makeSnatReservation(ICMP.PROTOCOL_NUMBER,
                              oldNwSrc, oldTpSrc, ip, oldTpSrc, nwDst, tpDst);
                if (reservation != null)
                    return reservation;
                if (++numTries > MAX_PORT_ALLOC_ATTEMPTS) {
                    log.warn("allocateSnatICMP failed to reserve {} nw src",
                             MAX_PORT_ALLOC_ATTEMPTS);
                    return null;
                }
            }
        }
        return null;
    }

    @Override
    public NwTpPair lookupSnatFwd(byte protocol, IPAddr oldNwSrc, int oldTpSrc_,
                                  IPAddr nwDst, int tpDst_) {
        int oldTpSrc = oldTpSrc_ & USHORT;
        int tpDst = tpDst_ & USHORT;
        String fwdKey = makeCacheKey(FWD_SNAT_PREFIX, protocol,
                                     oldNwSrc, oldTpSrc, nwDst, tpDst);
        String value = cache.getAndTouch(fwdKey);
        log.debug("lookupSnatFwd: key {} value {}", fwdKey, value);
        if (null == value)
            return null;
        NwTpPair pair = makePairFromString(value, fwdKey);
        if (null == pair)
            return null;
        // If the forward mapping was found, touch the reverse mapping too,
        // then schedule a refresh.
        String revKey = makeCacheKey(REV_SNAT_PREFIX, protocol,
                                     pair.nwAddr, pair.tpPort, nwDst, tpDst);
        cache.getAndTouch(revKey);
        scheduleRefresh(fwdKey, revKey);
        return pair;
    }

    @Override
    public NwTpPair lookupSnatRev(byte protocol, IPAddr newNwSrc, int newTpSrc_,
                                  IPAddr nwDst, int tpDst_) {
        int newTpSrc = newTpSrc_ & USHORT;
        int tpDst = tpDst_ & USHORT;
        String key = makeCacheKey(REV_SNAT_PREFIX, protocol, newNwSrc, newTpSrc,
                nwDst, tpDst);
        String value = cache.get(key);
        log.debug("lookupSnatRev: key {} value {}", key, value);
        return (null == value) ? null : makePairFromString(value, null);
    }

    @Override
    public void updateSnatTargets(Set<NatTarget> targets) {
        // TODO shouldn't this be a UnsupportedOperationException
        log.warn("updateSnatTargets - UNIMPLEMENTED: {}", targets);
    }

    /* natUnref() and natRef() update the reference count for a forwardKey,
     * while also maintaining the map of keys consistently up to date:
     *
     *  - When natRef() creates the first reference to a key, it adds the key
     *    to the map.
     *  - When natUnref() removes the last reference to a key, it removes the
     *    key from the map.
     *
     * Their synchronization is lockless, and this is how the possible races are
     * prevented, most are trivial but explained for completeness:
     *
     *   + refcount = 0
     *      - Two natRef() calls could race with each other. This is avoided
     *        by using putIfAbsent() to write to the map. Only the winner of
     *        this race will report to the caller that this is a new mapping,
     *        thus the caller can do further initialization on the new
     *        entry in confidence that no one else is going to to the same.
     *
     *        The loser will start over through a recursive call to natRef.
     *
     *      - natUnref() is a no-op in this case.
     *
     *   + refcount = 1
     *      - Two natRef() calls. No races are possible since the refcount is
     *        atomic.
     *
     *      - Two natUnref() calls could race with each other, albeit if that
     *        happened it would be due to a bug in the users of this class,
     *        because ref/unref ops are supposed to be symmetric.
     *
     *        If this happened, only the natUnref() that decrements the atomic
     *        counter to zero would go on and clean up the entry.
     *
     *      - A natRef() could race with a natUnref() call, only if natUnref()
     *        gets to the atomic op first. This is really the only non-trivial
     *        case:
     *
     *          - Upon noticing that the decrement operation brought the counter
     *            down to zero, natUnref() will remove the key from the map
     *            and proceed to do the cleanups (actually, just cancelling the
     *            future, but it could be anything).
     *
     *          - natRef() needs protection against natUnref() decrementing
     *            first, if that happens all runninng natRef() calls must
     *            be retried, and one of them will create a new mapping. This
     *            achieved through a conditional variant of the atomic
     *            increment: incrementIfGreaterThanAndGet(). After a successful
     *            call to this function, natRef() is assured that no natUnref()
     *            calls have decremented the counter to zero.
     */
    @Override
    public void natUnref(String fwdKey) {
        log.debug("natUnref: key {}", fwdKey);
        KeyMetadata keyData = fwdKeys.get(fwdKey);
        if (null == keyData || keyData.flowCount.decrementAndGet() != 0) {
            // this was not the last user of this key, or the key did not exist
            return;
        }

        if (fwdKeys.remove(fwdKey) != keyData)
            return;
        // Cancel refreshing of the nat keys
        log.debug("natUnref canceling refresh of key {}", fwdKey);
        if (null != keyData.revKey) {
            log.debug("natUnref canceling refresh of key {}", keyData.revKey);
        }
        if (null != keyData.future) {
            log.debug("natUnref found future to cancel.");
            keyData.future.cancel(false);
        }
    }

    private static int incrementIfGreaterThanAndGet(AtomicInteger integer,
                                                    int value) {
        for (;;) {
            int i = integer.get();
            if (i <= value)
                return i;
            if (integer.compareAndSet(i, i+1))
                return i+1;
        }
    }

    private boolean natRef(String fwdKey, String revKey) {
        log.debug("incrementing reference count for key {}", fwdKey);
        KeyMetadata keyData = fwdKeys.get(fwdKey);
        if (null == keyData) {
            // New key. Put a new metadata object into the map. If somebody else
            // races with us and adds it first, start over with a recursive call
            keyData = new KeyMetadata(revKey);
            KeyMetadata oldV = fwdKeys.putIfAbsent(fwdKey, keyData);
            return (null == oldV) ? true : natRef(fwdKey, revKey);
        } else {
             // Known key. If somebody raced with us to delete the key while
             // we increment the refcount, start over with a recursive call.
            if (incrementIfGreaterThanAndGet(keyData.flowCount, 0) > 1)
                return false;
            else
                return natRef(fwdKey, revKey);
        }
    }

}
