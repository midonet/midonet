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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.midonet.cache.LoadingCache;
import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.serialization.Serializer;
import org.midonet.midolman.state.zkManagers.PortGroupZkManager;
import org.midonet.util.eventloop.Reactor;
import org.midonet.util.functors.Callback1;

/**
 * An implementation of {@link org.midonet.cache.LoadingCache} that
 * stores UUID/PortGroup entries.
 *
 * This class uses ZooKeeper watchers to get notifications when its
 * PortGroupConfigs
 * change. PortGroupConfigs in the cache are always consistent with respect to
 * ZooKeeper until they are removed.
 *
 * This class won't take care of the expiration, since it has no way of knowing
 * when we last asked for a port, the VirtualTopologyActor is caching data
 * and won't call this class every time.
 */

//TODO(ross) implement a way of freeing space
public class PortGroupCache extends LoadingCache<UUID, PortGroupZkManager.PortGroupConfig> {
    private static final Logger log =
            LoggerFactory.getLogger(PortConfigCache.class);

    ZkConnectionAwareWatcher connectionWatcher;

    Serializer serializer;

    private PortGroupZkManager portGroupMgr;
    private Set<Callback1<UUID>> watchers = new HashSet<Callback1<UUID>>();

    public PortGroupCache(Reactor reactor, PortGroupZkManager portGroupMgr,
            ZkConnectionAwareWatcher connWatcher, Serializer serializer) {
        super(reactor);
        this.portGroupMgr = portGroupMgr;
        connectionWatcher = connWatcher;
        this.serializer = serializer;
    }

    public <T extends PortGroupZkManager.PortGroupConfig> T get(UUID key, Class<T> clazz) {
        PortGroupZkManager.PortGroupConfig config = get(key);
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
    protected PortGroupZkManager.PortGroupConfig load(UUID key) {
        PortGroupWatcher watcher = new PortGroupWatcher(key);
        try {
            return portGroupMgr.get(key, watcher);
        } catch (StateAccessException e) {
            log.warn("Exception retrieving PortGroupConfig", e);
            connectionWatcher.handleError(
                    "PortWatcher:" + key.toString(), watcher, e);
        } catch (SerializationException e) {
            log.error("Could not deserialize PortGroupConfig key {}", key);
        }

        return null;
    }

    // This maintains consistency of the cached port configs w.r.t ZK.
    private class PortGroupWatcher implements Runnable {
        UUID id;

        PortGroupWatcher(UUID id) {
            this.id = id;
        }

        @Override
        public void run() {
            // Update the value and re-register for ZK notifications.
            try {
                PortGroupZkManager.PortGroupConfig config = portGroupMgr.get(id, this);
                put(id, config);
                notifyWatchers(id);
            } catch (NoStatePathException e) {
                log.debug("PortGroup {} has been deleted", id);
                put(id, null);
            } catch (StateAccessException e) {
                // If the ZK lookup fails, the cache keeps the old value.
                log.warn("Exception refreshing PortConfig", e);
                connectionWatcher.handleError(
                        "PortGroupWatcher:" + id.toString(), this, e);
            } catch (SerializationException e) {
                log.error("Could not serialize PortGroupConfig {}", id);
            }
        }
    }
}
