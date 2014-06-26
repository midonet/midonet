/*
 * Copyright 2011 Midokura KK
 */

package org.midonet.midolman.layer4;

import java.util.Iterator;
import java.util.NavigableSet;
import java.util.Random;
import java.util.Set;
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
        public ScheduledFuture<?> future;
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
        this.ipToFreePortsMap = new ConcurrentHashMap<>();
        this.routerId = routerId;
        this.rtrIdStr = routerId.toString();
        this.cache = cache;
        this.refreshSeconds = cache.getExpirationSeconds() / 2;
        this.reactor = reactor;
        this.rand = new Random();
        this.fwdKeys = new ConcurrentHashMap<String, KeyMetadata>();
    }

    private class RefreshNatMappings implements Runnable {
        final String fwdKey;
        final int expirationSeconds;

        private RefreshNatMappings(String fwdKey, int expirationSeconds) {
            this.fwdKey = fwdKey;
            this.expirationSeconds = expirationSeconds;
        }

        private void refreshKey(String key) {
            if (null == key) {
                log.error("SCREAM: refreshKey() got a null key");
                return;
            }
            log.debug("RefreshNatMappings refresh key {}", key);
            try {
                String val = cache.getAndTouchWithExpiration(key,
                        expirationSeconds);
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
            reactor.schedule(this, expirationSeconds, TimeUnit.SECONDS);
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
        return allocateDnat(protocol, nwSrc, tpSrc_, oldNwDst, oldTpDst_, nats,
                cache.getExpirationSeconds());
    }

    @Override
    public NwTpPair allocateDnat(byte protocol, IPAddr nwSrc, int tpSrc_,
                                 IPAddr oldNwDst, int oldTpDst_,
                                 Set<NatTarget> nats,
                                 int overrideCacheTimeoutSeconds) {

        // TODO(pino) get rid of these after converting ports to int.
        final int tpSrc = tpSrc_ & USHORT;
        final int oldTpDst = oldTpDst_ & USHORT;
        log.debug("allocateDnat: proto {} nwSrc {} tpSrc {} oldNwDst {} " +
                  "oldTpDst {} nats {}", new Object[] { protocol,
                      nwSrc, tpSrc, oldNwDst, oldTpDst, nats });

        NatTarget nat = chooseRandomNatTarget(nats);
        IPv4Addr newNwDst = (IPv4Addr)chooseRandomIp(nat);
        final int newTpDst =  (protocol == ICMP.PROTOCOL_NUMBER)
                              ? oldTpDst : chooseRandomPort(nat);

        // newNwDst needs to be the same type as nwSrc, nwDst
        log.debug("{} DNAT allocated new DST {}:{} to flow from {}:{} to "
                + "{}:{} protocol {}", new Object[] { rtrIdStr,
                        newNwDst, newTpDst, nwSrc, tpSrc, oldNwDst,
                        oldTpDst, protocol});

        // TODO(pino): can't one write to the cache suffice?
        String fwdKey = makeCacheKey(FWD_DNAT_PREFIX, protocol,
                                     nwSrc, tpSrc, oldNwDst, oldTpDst);
        cache.setWithExpiration(fwdKey, makeCacheValue(newNwDst, newTpDst),
                overrideCacheTimeoutSeconds);
        String revKey = makeCacheKey(REV_DNAT_PREFIX, protocol,
                                     nwSrc, tpSrc, newNwDst, newTpDst);
        cache.setWithExpiration(revKey, makeCacheValue(oldNwDst, oldTpDst),
                overrideCacheTimeoutSeconds);
        log.debug("allocateDnat fwd key {} and rev key {}", fwdKey, revKey);
        scheduleRefreshWithExpiration(fwdKey, revKey,
                overrideCacheTimeoutSeconds);
        return new NwTpPair(newNwDst, newTpDst, fwdKey);
    }

    private void scheduleRefresh(String fwdKey, String revKey) {
        scheduleRefreshWithExpiration(fwdKey, revKey, refreshSeconds);
    }

    private void scheduleRefreshWithExpiration(String fwdKey, String revKey,
                                               int overrideRefreshSeconds) {
        boolean isNew = natRef(fwdKey, revKey);
        KeyMetadata keyData = fwdKeys.get(fwdKey);
        if (null == keyData) {
            log.error("SCREAM: key not found after natRef()");
            return;
        }

        if (isNew) {
            keyData.future = reactor.schedule(
                    new RefreshNatMappings(fwdKey, overrideRefreshSeconds),
                    overrideRefreshSeconds,
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
        return lookupDnatFwd(protocol, nwSrc, tpSrc_,
                             oldNwDst, oldTpDst_,
                             cache.getExpirationSeconds());
    }

    @Override
    public NwTpPair lookupDnatFwd(byte protocol, IPAddr nwSrc, int tpSrc_,
                                  IPAddr oldNwDst, int oldTpDst_,
                                  int expirationSeconds) {
        int tpSrc = tpSrc_ & USHORT;
        int oldTpDst = oldTpDst_ & USHORT;
        String fwdKey = makeCacheKey(FWD_DNAT_PREFIX, protocol,
                nwSrc, tpSrc, oldNwDst, oldTpDst);
        String value = cache.getAndTouchWithExpiration(fwdKey,
                expirationSeconds);
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
        cache.getAndTouchWithExpiration(revKey, expirationSeconds);
        scheduleRefreshWithExpiration(fwdKey, revKey, expirationSeconds);
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

    @Override
    public void deleteDnatEntry(byte protocol, IPAddr nwSrc, int tpSrc_,
                                  IPAddr oldNwDst, int oldTpDst_,
                                  IPAddr newNwDst, int newTpDst) {

        int tpSrc = tpSrc_ & USHORT;
        int oldTpDst = oldTpDst_ & USHORT;
        String fwdKey = makeCacheKey(FWD_DNAT_PREFIX, protocol,
                nwSrc, tpSrc, oldNwDst, oldTpDst);
        String revKey = makeCacheKey(REV_DNAT_PREFIX, protocol,
                nwSrc, tpSrc, newNwDst, newTpDst);

        // Remove cache key and cancel future so it won't be refreshed
        KeyMetadata keyData = fwdKeys.get(fwdKey);
        if (keyData != null) {
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

        cache.delete(fwdKey);
        cache.delete(revKey);

        log.debug("deleteDnatEntry: fwdKey {} revKey {}", fwdKey, revKey);
    }


    /*
     * Note that this method contains a race between the moment we check Cass
     * for an existing key, and setting the 2 entries.
     */
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

    private NatTarget chooseRandomNatTarget(Set<NatTarget> nats) {
        if (nats.isEmpty())
            throw new IllegalArgumentException("Empty nat targets");
        int natPos = rand.nextInt(nats.size());
        Iterator<NatTarget> it = nats.iterator();
        NatTarget nat = null;
        for (int i = 0; i <= natPos && it.hasNext(); i++)
            nat = it.next();
        return nat;
    }

    private IPAddr chooseRandomIp(NatTarget nat) {
        return nat.nwStart.randomTo(nat.nwEnd, rand);
    }

    private int chooseRandomPort(NatTarget nat) {
        int tpStart = nat.tpStart & USHORT;
        int tpEnd = nat.tpEnd & USHORT;
        return rand.nextInt(tpEnd - tpStart + 1) + tpStart;
    }

    /**
     * This method offers unified SNAT allocation for any supported protocol
     * verified by isNatSupported. It will take a 4-tuple and try to create a
     * translated 4-tuple. Only source values will be translated.
     *
     * The allocation is made randomly, selecting first a random nat target,
     * then ip and port. We will retry for MAX_PORT_ALLOC_ATTEMPTS.
     *
     * Given this method users are encouraged to provide values that minimize the
     * chance of collisions when performing lookups.
     *
     * ICMP ECHO messages this is achieved using the icmp_id field as both tpSrc
     * and tpDst, relying on the random generation of these values as a
     * sufficient prevention of collisions.
     *
     * @param protocol of the packet
     * @param oldNwSrc old src ip, may get translated in the return pair
     * @param oldTpSrc_ old src transport, may get translated in the return pair
     * @param nwDst dst ip, will not get translated in the return pair
     * @param tpDst_ dst transport, will not get translated in the return pair
     * @param nats all the nat targets available for this translation
     * @return a nat pair, or null if it was not possible to reserve one.
     */
    @Override
    public NwTpPair allocateSnat(byte protocol, IPAddr oldNwSrc, int oldTpSrc_,
                                 IPAddr nwDst, int tpDst_, Set<NatTarget> nats) {

        final int oldTpSrc = oldTpSrc_ & USHORT;
        final int tpDst = tpDst_ & USHORT;

        if (protocol == ICMP.PROTOCOL_NUMBER) {
            return allocateSnatICMP(oldNwSrc, oldTpSrc, nwDst, tpDst, nats);
        }

        int numTries = 0;
        while (numTries++ < MAX_PORT_ALLOC_ATTEMPTS) {
            NatTarget nat = chooseRandomNatTarget(nats);
            IPAddr newNwSrc = chooseRandomIp(nat);
            int newTpSrc = chooseRandomPort(nat);
            NwTpPair reservation = makeSnatReservation(protocol,
                                                       oldNwSrc, oldTpSrc,
                                                       newNwSrc, newTpSrc,
                                                       nwDst, tpDst);
            if (reservation != null) {
                return reservation;
            }
        }

        log.warn("Failed to allocate snat after {} attempts", numTries);

        return null;
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
        final int oldTpSrc = oldTpSrc_ & USHORT;
        final int tpDst = tpDst_ & USHORT;
        int numTries = 0;

        while (numTries++ < MAX_PORT_ALLOC_ATTEMPTS) {
            NatTarget nat = chooseRandomNatTarget(nats);
            IPAddr ip = chooseRandomIp(nat);
            NwTpPair reservation = makeSnatReservation(ICMP.PROTOCOL_NUMBER,
                                                       oldNwSrc, oldTpSrc, ip,
                                                       oldTpSrc, nwDst, tpDst);
            if (reservation != null)
                return reservation;

        }
        log.warn("allocateSnatICMP failed to reserve {} nw src",
                 MAX_PORT_ALLOC_ATTEMPTS);
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
        log.error("updateSnatTargets - UNIMPLEMENTED: {}", targets);
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
