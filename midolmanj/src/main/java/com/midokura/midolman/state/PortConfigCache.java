/*
 * Copyright 2012 Midokura Europe SARL
 */

package com.midokura.midolman.state;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.cache.LoadingCache;
import com.midokura.midolman.state.zkManagers.PortZkManager;
import com.midokura.util.eventloop.Reactor;
import com.midokura.util.functors.Callback1;

/**
 * An implementation of {@link com.midokura.cache.LoadingCache} that
 * stores UUID/PortConfig entries.
 *
 * This class uses ZooKeeper watchers to get notifications when its PortConfigs
 * change. PortConfigs in the cache are always consistent with respect to
 * ZooKeeper until they are removed.
 *
 * This class won't take care of the expiration, since it has no way of knowing
 * when we last asked for a port, the VirtualTopologyActor is caching data
 * and won't call this class every time.
 */

//TODO(ross) implement a way of freeing space
public class PortConfigCache extends LoadingCache<UUID, PortConfig> {
    private static final Logger log =
            LoggerFactory.getLogger(PortConfigCache.class);

    private PortZkManager portMgr;
    private Set<Callback1<UUID>> watchers = new HashSet<Callback1<UUID>>();

    public PortConfigCache(Reactor reactor, Directory zkDir,
                           String zkBasePath) {
        super(reactor);
        portMgr = new PortZkManager(zkDir, zkBasePath);
    }

    public <T extends PortConfig> T get(UUID key, Class<T> clazz) {
        PortConfig config = get(key);
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

    private void notifyWatchers(UUID portId) {
        // TODO(pino): should these be called asynchronously?
        for (Callback1<UUID> watcher : watchers) {
            watcher.call(portId);
        }
    }

    @Override
    protected PortConfig load(UUID key) {
        try {
            return portMgr.get(key, new PortWatcher(key));
        } catch (StateAccessException e) {
            log.error("Exception retrieving PortConfig", e);
        }
        return null;
    }

    // This maintains consistency of the cached port configs w.r.t ZK.
    private class PortWatcher implements Runnable {
        UUID portID;

        PortWatcher(UUID portID) {
            this.portID = portID;
        }

        @Override
        public void run() {
                // Update the value and re-register for ZK notifications.
                try {
                    PortConfig config = portMgr.get(portID, this);
                    put(portID, config);
                    notifyWatchers(portID);
                } catch (StateAccessException e) {
                    // If the ZK lookup fails, the cache keeps the old value.
                    log.error("Exception refreshing PortConfig", e);
                }
        }
    }
}
