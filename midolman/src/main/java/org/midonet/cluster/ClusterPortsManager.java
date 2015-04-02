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

package org.midonet.cluster;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import javax.inject.Inject;
import javax.inject.Named;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import rx.functions.Action1;
import rx.functions.Func1;

import org.midonet.cluster.client.PortBuilder;
import org.midonet.midolman.cluster.zookeeper.ZkConnectionProvider;
import org.midonet.midolman.state.PortConfig;
import org.midonet.midolman.state.PortConfigCache;
import org.midonet.midolman.state.StateAccessException;
import org.midonet.midolman.state.zkManagers.PortZkManager;
import org.midonet.midolman.topology.devices.Port;
import org.midonet.midolman.topology.devices.PortFactory;
import org.midonet.util.eventloop.Reactor;
import org.midonet.util.functors.Callback1;

public class ClusterPortsManager extends ClusterManager<PortBuilder> {

    PortConfigCache portConfigCache;
    // These watchers belong to classes of the cluster (eg ClusterBridgeManager)
    // they don't implement the PortBuilder interface
    Map<UUID, Runnable> clusterWatchers = new HashMap<UUID, Runnable>();

    @Inject
    @Named(ZkConnectionProvider.DIRECTORY_REACTOR_TAG)
    Reactor reactorLoop;

    private static final Logger log = LoggerFactory
        .getLogger(ClusterPortsManager.class);

    @Inject
    PortZkManager portMgr;

    @Inject
    public ClusterPortsManager(PortConfigCache configCache) {
        portConfigCache = configCache;
        configCache.addWatcher(getPortsWatcher());
    }

    protected <T extends PortConfig> T getPortConfigAndRegisterWatcher(
            final UUID id, Class<T> clazz, Runnable watcher) {
        T config = portConfigCache.getSync(id, clazz);
        clusterWatchers.put(id, watcher);
        return config;
    }

    @Override
    protected void onNewBuilder(final UUID id) {
        PortBuilder builder = getBuilder(id);
        if (builder != null) {
            builder.setActive(isActive(id, builder));
            log.debug("Build port {}", id);
            builder.build();
        }
    }

    @Override
    protected void getConfig(final UUID id) {
        portConfigCache.get(id).observeOn(reactorLoop.rxScheduler())
            .filter(new Func1<PortConfig, Boolean>() {
                    @Override
                    public Boolean call(PortConfig config) {
                        return (config != null);
                    }
                })
            .subscribe(new Action1<PortConfig>() {
                    @Override
                    public void call(PortConfig config) {
                        buildFromConfig(id, config);
                    }
                },
                new Action1<Throwable>() {
                    @Override
                    public void call(Throwable t) {
                        log.info("Exception thrown getting config for {}",
                                 id, t);
                    }
                });
    }

    private void buildFromConfig(UUID id, PortConfig config) {
        PortBuilder builder = getBuilder(id);
        Port port = PortFactory.from(config);

        if (builder != null) {
            builder.setPort(port);
            log.debug("Build port {}, id {}", port, id);
            builder.build();
        }

        // this runnable notifies the classes in the cluster of a change in the
        // port configuration
        Runnable watcher = clusterWatchers.get(id);
        if (null != watcher)
            watcher.run();
    }

    private boolean isActive(final UUID portId, final Runnable watcher) {
        try {
            return portMgr.isActivePort(portId, watcher);
        } catch (StateAccessException e) {
            log.warn("Exception retrieving Port liveness", e);
            connectionWatcher.handleError(
                    "Port Liveness:" + portId, watcher, e);
        }
        return false;
    }

    private boolean isActive(final UUID portId, final PortBuilder builder) {
        return isActive(portId, aliveWatcher(portId, builder));
    }

    public Runnable aliveWatcher(final UUID portId, final PortBuilder builder) {
        return new Runnable() {
            @Override
            public void run() {
                log.debug("Port liveness changed: {}", portId);
                builder.setActive(isActive(portId, this));
                builder.build();
            }
        };
    }

    public Callback1<UUID> getPortsWatcher(){
        return new Callback1<UUID>() {
            @Override
            public void call(UUID portId) {
               // this will be executed by the watcher in PortConfigCache
               // that is triggered by ZkDirectory, that has the same reactor as
               // the cluster client.
                log.debug("Port changed: {}", portId);
               getConfig(portId);
            }
        };
    }
}
