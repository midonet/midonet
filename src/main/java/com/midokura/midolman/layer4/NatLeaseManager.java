package com.midokura.midolman.layer4;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.NavigableMap;
import java.util.NavigableSet;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import org.apache.zookeeper.KeeperException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.midolman.rules.NatTarget;
import com.midokura.midolman.state.RouterDirectory;
import com.midokura.midolman.util.Cache;

public class NatLeaseManager implements NatMapping {

    private static final Logger log = LoggerFactory
            .getLogger(NatLeaseManager.class);

    // The following maps IP addresses to ordered lists of free ports.
    // These structures are meant to be shared by all rules/nat targets.
    // So nat targets for different rules can overlap and we'll still avoid
    // collisions. That's why we don't care about the nat target here.
    // Note that we use a NaviableSet instead of a simple list because different
    // nat targets might use different port ranges for the same ip.
    // Also note that we don't care about ip ranges - nat targets with more than
    // one ip in their range get broken up into separate entries here.
    // This map should be cleared if we lose our connection to ZK.
    Map<Integer, NavigableSet<Short>> ipToFreePortsMap;
    private RouterDirectory routerDir;
    private UUID routerId;
    private Cache cache;
    private Random rand;

    public NatLeaseManager(RouterDirectory routerDir, UUID routerId, Cache cache) {
        this.routerDir = routerDir;
        this.ipToFreePortsMap = new HashMap<Integer, NavigableSet<Short>>();
        this.routerId = routerId;
        this.cache = cache;
        this.rand = new Random();
    }

    @Override
    public NwTpPair allocateDnat(int nwSrc, short tpSrc, int oldNwDst,
            short oldTpDst, Set<NatTarget> nats) {
        // This throws IllegalArgumentException if nats.size() is zero.
        int natPos = rand.nextInt(nats.size());
        Iterator<NatTarget> iter = nats.iterator();
        NatTarget nat = null;
        for (int i = 0; i <= natPos; i++)
            nat = iter.next();
        int newNwDst = rand.nextInt(nat.nwEnd - nat.nwStart + 1) + nat.nwStart;
        short newTpDst = (short) (rand.nextInt(nat.tpEnd - nat.tpStart + 1) + nat.tpStart);
        cache.set(String.format("dnatfwd%8x%d%8x%d", nwSrc, tpSrc, oldNwDst,
                oldTpDst), String.format("%8x/%d", newNwDst, newTpDst));
        cache.set(String.format("dnatrev%8x%d%8x%d", nwSrc, tpSrc, newNwDst,
                newTpDst), String.format("%8x/%d", oldNwDst, oldTpDst));
        return new NwTpPair(newNwDst, newTpDst);
    }

    private NwTpPair lookupNwTpPair(String key) {
        String value = cache.get(key);
        if (null == value)
            return null;
        String[] parts = value.split("/");
        return new NwTpPair((int) Long.parseLong(parts[0], 16), (short) Integer
                .parseInt(parts[1]));
    }

    @Override
    public NwTpPair lookupDnatFwd(int nwSrc, short tpSrc, int oldNwDst,
            short oldTpDst) {
        return lookupNwTpPair(String.format("dnatfwd%8x%d%8x%d", nwSrc, tpSrc,
                oldNwDst, oldTpDst));
    }

    @Override
    public NwTpPair lookupDnatRev(int nwSrc, short tpSrc, int newNwDst,
            short newTpDst) {
        return lookupNwTpPair(String.format("dnatrev%8x%d%8x%d", nwSrc, tpSrc,
                newNwDst, newTpDst));
    }

    @Override
    public NwTpPair allocateSnat(int oldNwSrc, short oldTpSrc, int nwDst,
            short tpDst, Set<NatTarget> nats) {
        // First try to find a port in a block we've already leased.
        for (NatTarget tg : nats) {
            for (int ip = tg.nwStart; ip <= tg.nwEnd; ip++) {
                NavigableSet<Short> freePorts = ipToFreePortsMap.get(ip);
                if (null != freePorts) {
                    // Look for a port in the desired range
                    Short port = freePorts.ceiling(tg.tpStart);
                    if (null != port && port <= tg.tpEnd) {
                        // We've found a free port.
                        freePorts.remove(port);
                        // Check memcached to make sure the port's really free.
                        return new NwTpPair(ip, port);
                    }
                    // Else - no free ports for this ip and port range
                }
            }
            // No free ports for this NatTarget
        }
        // None of our leased blocks were suitable. Try leasing another block.
        // TODO: Do something smarter. See:
        // https://sites.google.com/a/midokura.jp/wiki/midonet/srcnat-block-reservations
        int block_size = 100; // TODO: make this configurable?
        int numExceptions = 0;
        for (NatTarget tg : nats) {
            for (int ip = tg.nwStart; ip <= tg.nwEnd; ip++) {
                NavigableSet<Short> reservedBlocks;
                try {
                    reservedBlocks = routerDir.getSnatBlocks(routerId, ip);
                } catch (Exception e) {
                    return null;
                }
                // Note that Shorts in this sorted set should only be
                // multiples of 100 because that's how we avoid
                // collisions/re-leasing. A Short s represents a lease on
                // the port range [s, s+99] inclusive.
                // Round down tpStart to the nearest 100.
                short block = (short) ((tg.tpStart / block_size) * block_size);
                Iterator<Short> iter = reservedBlocks.tailSet(block, true)
                        .iterator();
                // Find the first lowPort + 100*x that isn't in the tail-set
                // and is less than tg.tpEnd
                while (iter.hasNext()) {
                    Short lease = iter.next();
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
                    if (block > tg.tpEnd)
                        break;
                }
                if (block > tg.tpEnd)
                    // No free blocks for this ip. Try the next ip.
                    break;
                try {
                    routerDir.addSnatReservation(routerId, ip, block);
                } catch (Exception e) {
                    numExceptions++;
                    if (numExceptions > 1)
                        return null;
                    continue;
                }
                // Expand the port block.
                NavigableSet<Short> freePorts = ipToFreePortsMap.get(ip);
                if (null == freePorts) {
                    freePorts = new TreeSet<Short>();
                    ipToFreePortsMap.put(ip, freePorts);
                }
                for (int i = 0; i < block_size; i++)
                    freePorts.add((short) (block + i));
                // Now, starting with the smaller of 'block' and tg.tpStart
                // see if the mapping really is free in Memcached by making sure
                // that the reverse mapping isn't already taken. Note that the
                // common case for snat requires 4 calls to Memcached (one to
                // check whether we've already seen the forward flow, one to
                // make sure the newIp, newPort haven't already been used with
                // the nwDst and tpDst, and 2 to actually store the forward
                // and reverse mappings).
                short freePort = block;
                if (freePort < tg.tpStart)
                    freePort = tg.tpStart;
                String reverseKey;
                while (true) {
                    freePorts.remove(freePort);
                    reverseKey = String.format("snatrev%8x%d%8x%d", ip,
                            freePort, nwDst, tpDst);
                    if (null != cache.get(reverseKey)) {
                        log.warn("Snat encountered a collision in the reverse"
                                + " mapping for %8x,%d.", ip, freePort);
                        freePort++;
                        if (0 == freePort % block_size || freePort > tg.tpEnd)
                            return null;
                        continue;
                    }
                    // If we got here, we can use this port.
                    cache.set(String.format("snatfwd%8x%d%8x%d", oldNwSrc,
                            oldTpSrc, nwDst, tpDst), String.format("%8x/%d",
                            ip, freePort));
                    cache.set(reverseKey, String.format("%8x/%d", oldNwSrc,
                            oldTpSrc));
                    return new NwTpPair(ip, freePort);
                }
            } // End for loop over ip addresses in a nat target.
        } // End for loop over nat targets.
        return null;
    }

    @Override
    public NwTpPair lookupSnatFwd(int oldNwSrc, short oldTpSrc, int nwDst,
            short tpDst) {
        return lookupNwTpPair(String.format("snatfwd%8x%d%8x%d", oldNwSrc,
                oldTpSrc, nwDst, tpDst));
    }

    @Override
    public NwTpPair lookupSnatRev(int newNwSrc, short newTpSrc, int nwDst,
            short tpDst) {
        return lookupNwTpPair(String.format("snatrev%8x%d%8x%d", newNwSrc,
                newTpSrc, nwDst, tpDst));
    }

    @Override
    public void updateSnatTargets(Set<NatTarget> targets) {
        // TODO Auto-generated method stub

    }

}
