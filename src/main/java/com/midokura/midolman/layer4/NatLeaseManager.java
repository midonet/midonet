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

import com.midokura.midolman.rules.NatTarget;
import com.midokura.midolman.state.RouterDirectory;
import com.midokura.midolman.util.Cache;

public class NatLeaseManager implements NatMapping {

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

    public NwTpPair findFreeBlock(Set<NatTarget> nats) {
        // TODO: Do something smarter. See:
        // https://sites.google.com/a/midokura.jp/wiki/midonet/srcnat-block-reservations
        int block_size = 100; // TODO: make this configurable?
        int numExceptions = 0;
        for (NatTarget tg : nats) {
            for (int ip = tg.nwStart; ip < tg.nwEnd; ip++) {
                NavigableSet<Short> reservedBlocks = routerDir.getSnatBlocks(
                        routerId, ip);
                // Note that Shorts in this sorted set should only be
                // multiples of 100 because that's how we avoid
                // collisions/re-leasing. A Short s represents a lease on
                // the port range [s, s+99] inclusive.
                // Round down tpStart to the nearest 100.
                short block = (short) (tg.tpStart / block_size);
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
                return (block < tg.tpStart) ? new NwTpPair(ip, tg.tpStart)
                        : new NwTpPair(ip, block);
            }
            // Try the next nat.
        }
        return null;
    }

    @Override
    public NwTpPair allocateSnat(int oldNwSrc, short oldTpSrc, int nwDst,
            short tpDst, Set<NatTarget> nats) {
        // First try to find a port in a block we've already leased.
        for (NatTarget tg : nats) {
            for (int ip = tg.nwStart; ip < tg.nwEnd; ip++) {
                NavigableSet<Short> freePorts = ipToFreePortsMap.get(ip);
                if (null != freePorts) {
                    // Look for a port in the desired range
                    Short port = freePorts.ceiling(tg.tpStart);
                    if (port <= tg.tpEnd) {
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
        NwTpPair block = findFreeBlock(nats);
        if (null == block)
            return null;
        int newNwSrc = block.nwAddr;
        short newTpSrc = block.tpPort;
        // TODO(pino): check memcached, is the port really free? Otherwise,
        // try the next port. Might need compare-and-set operation on cache.
        cache.set(String.format("snatfwd%8x%d%8x%d", oldNwSrc, oldTpSrc, nwDst,
                tpDst), String.format("%8x/%d", newNwSrc, newTpSrc));
        cache.set(String.format("snatrev%8x%d%8x%d", newNwSrc, newTpSrc, nwDst,
                tpDst), String.format("%8x/%d", oldNwSrc, oldTpSrc));
        return new NwTpPair(newNwSrc, newTpSrc);
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
