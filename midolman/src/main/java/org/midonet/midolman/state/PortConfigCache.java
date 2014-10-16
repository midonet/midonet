/*
 * Copyright 2014 Midokura SARL
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.midonet.midolman.state;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import com.google.inject.Inject;
import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.serialization.Serializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.midonet.cache.LoadingCache;
import org.midonet.midolman.state.zkManagers.PortZkManager;
import org.midonet.util.eventloop.Reactor;
import org.midonet.util.functors.Callback1;

/**
 * An implementation of {@link org.midonet.cache.LoadingCache} that
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

    ZkConnectionAwareWatcher connectionWatcher;

    Serializer serializer;

    private PortZkManager portMgr;
    private Set<Callback1<UUID>> watchers = new HashSet<Callback1<UUID>>();

    public PortConfigCache(Reactor reactor, Directory zkDir,
            String zkBasePath, ZkConnectionAwareWatcher connWatcher,
            Serializer serializer) {
        super(reactor);
        portMgr = new PortZkManager(zkDir, zkBasePath, serializer);
        connectionWatcher = connWatcher;
        this.serializer = serializer;
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
        PortWatcher watcher = new PortWatcher(key);
        try {
            return portMgr.get(key, watcher);
        } catch (StateAccessException e) {
            log.warn("Exception retrieving PortConfig", e);
            connectionWatcher.handleError(
                    "PortWatcher:" + key.toString(), watcher, e);
        } catch (SerializationException e) {
            log.error("Could not deserialize PortConfig key {}", key);
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
            } catch (NoStatePathException e) {
                log.debug("Port {} has been deleted", portID);
                put(portID, null);
            } catch (StateAccessException e) {
                // If the ZK lookup fails, the cache keeps the old value.
                log.warn("Exception refreshing PortConfig", e);
                connectionWatcher.handleError(
                        "PortWatcher:" + portID.toString(), this, e);
            } catch (SerializationException e) {
                log.error("Could not serialize PortConfig {}",
                        portID.toString());
            }
        }
    }
}
