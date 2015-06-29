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

import rx.Observable;
import rx.functions.Action1;
import rx.functions.Func1;
import rx.schedulers.Schedulers;

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

    public <T extends PortConfig> T getSync(UUID key, Class<T> clazz) {
        PortConfig config = getSync(key);
        try {
            return clazz.cast(config);
        } catch (ClassCastException e) {
            log.error("Failed to cast {} to a {}",
                      new Object[] {config, clazz, e});
            return null;
        }
    }

    public <T extends PortConfig> Observable<T> get(UUID key,
                                                    final Class<T> clazz) {
        return get(key).map(new Func1<PortConfig,T>() {
                @Override
                public T call(PortConfig config) {
                    try {
                        return clazz.cast(config);
                    } catch (ClassCastException cce) {
                        log.error("Failed to cast {} to a {}",
                                  new Object[] {config, clazz, cce});
                    }
                    return null;
                }
            });
    }

    public void addWatcher(Callback1<UUID> watcher) {
        watchers.add(watcher);
    }

    private void notifyWatchers(UUID portId) {
        // TODO(pino): should these be called asynchronously?
        for (Callback1<UUID> watcher : watchers) {
            watcher.call(portId);
        }
    }

    @Override
    protected PortConfig loadSync(UUID key) {
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

    @Override
    protected Observable<PortConfig> load(final UUID key) {
        final PortWatcher watcher = new PortWatcher(key);
        return portMgr.getWithObservable(key, watcher)
            .observeOn(Schedulers.trampoline())
            .doOnError(new Action1<Throwable>() {
                    @Override
                    public void call(Throwable t) {
                        if (t instanceof StateAccessException) {
                            log.warn("Exception retrieving PortConfig", t);
                            connectionWatcher.handleError(
                                    "PortWatcher:" + key.toString(),
                                    watcher, (StateAccessException)t);
                        } else if (t instanceof SerializationException) {
                            log.error("Could not deserialize PortConfig key {}",
                                      key);
                        }
                    }
                });
    }

    // This maintains consistency of the cached port configs w.r.t ZK.
    private class PortWatcher extends Directory.DefaultTypedWatcher {
        UUID portID;

        PortWatcher(UUID portID) {
            this.portID = portID;
        }

        @Override
        public void run() {
            // Update the value and re-register for ZK notifications.
            portMgr.getWithObservable(portID, this)
                .observeOn(reactor.rxScheduler())
                .subscribe(new Action1<PortConfig>() {
                        @Override
                        public void call(PortConfig config) {
                            put(portID, config);
                            notifyWatchers(portID);
                        }
                    },
                    new Action1<Throwable>() {
                        @Override
                        public void call(Throwable t) {
                            if (t instanceof NoStatePathException) {
                                log.debug("Port {} has been deleted", portID);
                                put(portID, null);
                            } else if (t instanceof StateAccessException) {
                                log.warn("Exception refreshing PortConfig", t);
                                connectionWatcher.handleError(
                                        "PortWatcher:" + portID.toString(),
                                        PortWatcher.this,
                                        (StateAccessException)t);
                            } else if (t instanceof SerializationException) {
                                log.error("Could not serialize PortConfig {}",
                                          portID.toString());
                            }
                        }
                    });
        }
    }
}
