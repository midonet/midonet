/*
 * Copyright 2012 Midokura Pte. Ltd.
 */

package com.midokura.midolman.state;

import com.midokura.cache.LoadingCache;
import com.midokura.midolman.state.zkManagers.BgpZkManager;
import com.midokura.midonet.cluster.data.BGP;
import com.midokura.util.eventloop.Reactor;
import com.midokura.util.functors.Callback1;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class BgpConfigCache extends LoadingCache<UUID, BGP> {
    private static final Logger log =
            LoggerFactory.getLogger(BgpConfigCache.class);

    BgpZkManager bgpMgr = null;
    private Set<Callback1<UUID>> watchers = new HashSet<Callback1<UUID>>();
    private Map<UUID, BgpWatcher> bgps = new HashMap<UUID, BgpWatcher>();


    public BgpConfigCache(Reactor reactor, Directory zkDir,
                           String zkBasePath) {
        super(reactor);
        bgpMgr = new BgpZkManager(zkDir, zkBasePath);
    }

    public <T extends BGP> T get(UUID key, Class<T> clazz) {
        BGP config = get(key);
        try {
            return clazz.cast(config);
        } catch (ClassCastException e) {
            log.error("Failed to cast {} to a {}",
                    new Object[] {config, clazz, e});
            return null;
        }
    }

    public void addWatcher(Callback1<UUID> watcher) {
        watchers.add(watcher);
    }

    public void removeWatcher(Callback1<UUID> watcher) {
        watchers.remove(watcher);
    }

    private void notifyWatchers(UUID bgpID) {
        for (Callback1<UUID> watcher : watchers) {
            watcher.call(bgpID);
        }
    }

    @Override
    protected BGP load(UUID bgpID) {
        try {
            return bgpMgr.getBGP(bgpID, new BgpWatcher(bgpID));
        } catch (StateAccessException e) {
            log.error("Exception retrieving PortConfig", e);
        }
        return null;
    }

    private class BgpWatcher implements Runnable {
        UUID bgpID;

        BgpWatcher(UUID bgpID) {
            this.bgpID = bgpID;
        }

        @Override
        public void run() {
            // Update the value and re-register for ZK notifications.
            try {
                BGP config = bgpMgr.getBGP(bgpID, this);
                put(bgpID, config);
                notifyWatchers(bgpID);
            } catch (StateAccessException e) {
                // If the ZK lookup fails, the cache keeps the old value.
                log.error("Exception refreshing BGP config", e);
            }
        }
    }

    // For a given port, we watch its list of BGPs in Zookeeper
    // BGPs are at this point mainly the bgp peers configurations
    // (one BGP class per peer)
    private class BgpPortWatcher implements Runnable {
        UUID bgpPortID;

        BgpPortWatcher(UUID bgpPortID) {
            this.bgpPortID = bgpPortID;
        }

        @Override
        public void run() {
            try {
                // We expect this list to be very small, usually one or two
                // entries. Searching in this list is not optimized due to
                // the small expected size.
                List<UUID> bgpList = bgpMgr.list(bgpPortID, this);

                // remove old entries
                for (UUID bgpID : bgps.keySet()) {
                    if (!bgpList.contains(bgpID)) {
                        bgps.remove(bgpID);
                    }
                }

                // add new entries (do not update existing ones)
                for (UUID bgpID : bgpList) {
                    if (!bgps.containsKey(bgpID)) {
                        bgps.put(bgpID, new BgpWatcher(bgpID));
                    }
                }

            } catch (StateAccessException e) {
                log.error("Exception refreshing list of bgps");
            }
        }
    }
}
