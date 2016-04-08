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

import scala.runtime.AbstractFunction0;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import rx.Observable;
import rx.functions.Action1;
import rx.functions.Func1;
import rx.schedulers.Schedulers;

import org.midonet.cache.LoadingCache;
import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.serialization.Serializer;
import org.midonet.midolman.state.zkManagers.PortGroupZkManager;
import org.midonet.midolman.topology.VirtualTopologyMetrics;
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
    private Set<Callback1<UUID>> watchers = new HashSet<>();

    public PortGroupCache(Reactor reactor, PortGroupZkManager portGroupMgr,
                          ZkConnectionAwareWatcher connWatcher,
                          Serializer serializer, VirtualTopologyMetrics metrics) {
        super(reactor);
        this.portGroupMgr = portGroupMgr;
        connectionWatcher = connWatcher;
        this.serializer = serializer;

        metrics.setPortGroupWatchers(new AbstractFunction0<Object>() {
            @Override public Object apply() {
                return watchers.size();
            }
        });
    }

    public <T extends PortGroupZkManager.PortGroupConfig>
        Observable<T> get(UUID key, final Class<T> clazz) {
        return get(key).map(
            new Func1<PortGroupZkManager.PortGroupConfig, T>() {
                @Override
                public T call(PortGroupZkManager.PortGroupConfig config) {
                    try {
                        return clazz.cast(config);
                    } catch (ClassCastException e) {
                        log.error("Failed to cast {} to a {}",
                                  config, clazz, e);
                        return null;
                    }
                }
            });
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
    protected PortGroupZkManager.PortGroupConfig loadSync(UUID key) {
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

    @Override
    protected Observable<PortGroupZkManager.PortGroupConfig> load(final UUID key) {
        final PortGroupWatcher watcher = new PortGroupWatcher(key);
        return portGroupMgr.getWithObservable(key, watcher)
            .observeOn(Schedulers.trampoline())
            .doOnError(
                new Action1<Throwable>() {
                    @Override
                    public void call(Throwable t) {
                        if (t instanceof NoStatePathException) {
                            log.trace("Port group {} has been deleted", key);
                        } else if (t instanceof StateAccessException) {
                            log.warn("Exception retrieving PortGroupConfig", t);
                            connectionWatcher.handleError(
                                    "PortWatcher:" + key.toString(), watcher,
                                    (StateAccessException)t);
                        } else if (t instanceof SerializationException) {
                            log.error("Could not deserialize PortGroupConfig key {}",
                                      key);
                        }
                    }
                });
    }

    // This maintains consistency of the cached port configs w.r.t ZK.
    private class PortGroupWatcher extends Directory.DefaultTypedWatcher {
        UUID id;

        PortGroupWatcher(UUID id) {
            this.id = id;
        }

        @Override
        public void run() {
            // Update the value and re-register for ZK notifications.
            portGroupMgr.getWithObservable(id, this)
                .observeOn(Schedulers.trampoline())
                .subscribe(
                    new Action1<PortGroupZkManager.PortGroupConfig>() {
                        @Override
                        public void call(PortGroupZkManager.PortGroupConfig config) {
                            put(id, config);
                            notifyWatchers(id);
                        }
                    },
                    new Action1<Throwable>() {
                        @Override
                        public void call(Throwable t) {
                            if (t instanceof NoStatePathException) {
                                log.debug("PortGroup {} has been deleted", id);
                                put(id, null);
                                notifyWatchers(id);
                            } else if (t instanceof StateAccessException) {
                                // If the ZK lookup fails, the cache keeps the old value.
                                log.warn("Exception refreshing PortConfig", t);
                                connectionWatcher.handleError(
                                        "PortGroupWatcher:" + id.toString(),
                                        PortGroupWatcher.this,
                                        (StateAccessException)t);
                            } else if (t instanceof SerializationException) {
                                log.error("Could not serialize PortGroupConfig {}", id);
                            }
                        }
                    });
        }
    }
}
