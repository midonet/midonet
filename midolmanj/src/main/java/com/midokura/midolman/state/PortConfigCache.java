/*
 * Copyright 2012 Midokura Europe SARL
 */

package com.midokura.midolman.state;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.util.eventloop.eventloop.Reactor;
import com.midokura.midolman.util.Callback1;
import com.midokura.midolman.util.LoadingCache;

/**
 * An implementation of {@link com.midokura.midolman.util.LoadingCache} that
 * stores UUID/PortConfig entries.
 *
 * This class uses ZooKeeper watchers to get notifications when its PortConfigs
 * change. PortConfigs in the cache are always consistent with respect to
 * ZooKeeper until they are removed.
 *
 * UUID/PortConfig entries may be removed if the UUID is not Pinned and
 * if the entry's last access time occurred more than 'expiryMillis'
 * milliseconds in the past. Removal of expired entries is done lazily and is
 * never guaranteed to occur.
 */
public class PortConfigCache extends LoadingCache<UUID, PortConfig> {
    private static final Logger log =
            LoggerFactory.getLogger(PortConfigCache.class);

    private PortZkManager portMgr;
    public static final long expiryMillis = TimeUnit.SECONDS.toMillis(300);
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
            // If the entry has already been removed, do nothing.
            if (!hasKey(portID))
                return;
            // If the key isn't pinned and has expired, remove it.
            if (!isPinned(portID) && getLastAccessTime(portID) + expiryMillis
                    <= reactor.currentTimeMillis()) {
                put(portID, null);
                return;
            } else {
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
}
