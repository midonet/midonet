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
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import javax.inject.Inject;
import javax.inject.Named;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.midonet.cluster.client.HealthMonitorBuilder;
import org.midonet.cluster.client.RouterBuilder;
import org.midonet.cluster.client.TunnelZones;
import org.midonet.cluster.data.TunnelZone;
import org.midonet.midolman.cluster.zookeeper.ZkConnectionProvider;
import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.state.Directory;
import org.midonet.midolman.state.StateAccessException;
import org.midonet.midolman.state.ZkConnectionAwareWatcher;
import org.midonet.midolman.state.ZkDirectory;
import org.midonet.midolman.state.zkManagers.TunnelZoneZkManager;
import org.midonet.util.eventloop.Reactor;


/**
 * Implementation of the Cluster.Client using ZooKeeper
 * Assumption:
 * - No cache, the caller of this class will have to implement its own cache
 * - Only one builder for UUID is allowed
 * - Right now it's single-threaded, we don't assure a thread-safe behaviour
 */
public class LocalClientImpl implements Client {

    private static final Logger log = LoggerFactory
            .getLogger(LocalClientImpl.class);

    @Inject
    ClusterPoolHealthMonitorMapManager poolHealthMonitorMapManager;

    @Inject
    ClusterHealthMonitorManager healthMonitorManager;

    @Inject
    TunnelZoneZkManager tunnelZoneZkManager;

    @Inject
    ClusterRouterManager routerManager;

    @Inject
    ZkConnectionAwareWatcher connectionWatcher;

    /**
     * We inject it because we want to use the same {@link Reactor} as
     * {@link ZkDirectory}
     */
    @Inject
    @Named(ZkConnectionProvider.DIRECTORY_REACTOR_TAG)
    Reactor reactorLoop;

    @Override
    public void getRouter(UUID routerID, RouterBuilder builder) {
        routerManager.registerNewBuilder(routerID, builder);
        log.debug("getRouter {}", routerID);
    }

    @Override
    public void getHealthMonitor(UUID healthMonitorId,
                                 HealthMonitorBuilder builder) {
        log.debug("getHealthMonitor {}", healthMonitorId);
        healthMonitorManager.registerNewBuilder(healthMonitorId, builder);
    }

    @Override
    public void getTunnelZones(final UUID zoneID,
                               final TunnelZones.BuildersProvider builders) {
        reactorLoop.submit(new Runnable() {
            @Override
            public void run() {
                try {
                    TunnelZone zone = readTunnelZone(zoneID, builders);
                    readHosts(zone,
                            new HashMap<>(),
                            builders);

                } catch (StateAccessException e) {
                    connectionWatcher.handleError(
                            "TunnelZone:" + zoneID.toString(), this, e);
                } catch (SerializationException e) {
                    log.error("Non recoverable error on serialization for" +
                            " TunnelZone:" + zoneID.toString());
                    throw new RuntimeException("Non recoverable error on " +
                            "serialization for TunnelZone:"
                            + zoneID.toString());
                }
            }
        });
    }

    private void readHosts(final TunnelZone zone,
                           final Map<UUID, TunnelZone.HostConfig> zoneHosts,
                           final TunnelZones.BuildersProvider builders) {

        Set<UUID> currentList = null;

        try {
            currentList =
                    tunnelZoneZkManager.getZoneMemberships(zone.getId(),
                            new Directory.DefaultTypedWatcher() {
                                @Override
                                public void pathChildrenUpdated(String path) {
                                    readHosts(
                                            zone,
                                            zoneHosts,
                                            builders);
                                }
                            });
        } catch (StateAccessException e) {
            log.error("Exception while reading hosts", e);
            Runnable retry = new Runnable() {
                @Override
                public void run() {
                    readHosts(zone, zoneHosts, builders);
                }
            };
            connectionWatcher.handleError("HostsForZone:" + zone.getId(), retry, e);
            return;
        }

        try {
            Set<TunnelZone.HostConfig> newMemberships =
                    new HashSet<TunnelZone.HostConfig>();
            Set<UUID> newHosts = new HashSet<UUID>();
            for (UUID uuid : currentList) {
                if (!zoneHosts.containsKey(uuid)) {
                    newHosts.add(uuid);
                }
            }
            newMemberships.addAll(
                    tunnelZoneZkManager.getZoneMembershipsForHosts(
                            zone.getId(), newHosts));

            Set<UUID> removedMemberships = new HashSet<UUID>();
            for (UUID uuid : zoneHosts.keySet()) {
                if (!currentList.contains(uuid)) {
                    removedMemberships.add(uuid);
                }
            }

            for (TunnelZone.HostConfig newHost : newMemberships) {
                triggerZoneMembershipChange(zone, newHost, builders, true);
                zoneHosts.put(newHost.getId(), newHost);
            }

            for (UUID removedHost : removedMemberships) {
                triggerZoneMembershipChange(zone, zoneHosts.get(removedHost),
                        builders, false);
                zoneHosts.remove(removedHost);
            }

        } catch (StateAccessException e) {
            // XXX(guillermo) I don't see how this block could be retried
            // without racing with the watcher installed by the first try{}
            // block or blocking the reactor by retrying in a loop right here.
            // The latter solution would only apply for timeout errors, not
            // disconnections.
            //
            // For now, this error is left unhandled.
            log.error("Exception while reading hosts", e);
        }catch (SerializationException e) {
            // There is no reason to retry for serialization error.
            log.error("Serialization error while reading hosts: ", e);
        }
    }

    private void triggerZoneMembershipChange(TunnelZone zone,
                                             TunnelZone.HostConfig hostConfig,
                                             TunnelZones.BuildersProvider buildersProvider,
                                             boolean added) {
        if (added) {
            buildersProvider
                    .getZoneBuilder()
                    .addHost(hostConfig.getId(), hostConfig);
        } else {
            buildersProvider
                    .getZoneBuilder()
                    .removeHost(hostConfig.getId(), hostConfig);
        }
    }

    private TunnelZone readTunnelZone(final UUID zoneID,
            final TunnelZones.BuildersProvider builders)
            throws StateAccessException, SerializationException {

        TunnelZone zone;
        try {
            zone = tunnelZoneZkManager.getZone(
                    zoneID,
                    new Directory.DefaultPersistentWatcher(connectionWatcher) {
                        @Override
                        public void pathDataChanged(String path) {
                            run();
                        }

                        @Override
                        public String describe() {
                            return "TunnelZone:" + zoneID.toString();
                        }

                        @Override
                        public void _run() throws StateAccessException {

                            try {
                                readTunnelZone(zoneID, builders);
                            } catch (SerializationException e) {
                                log.error("Serialization error");
                            }
                        }
                    });
        } catch (StateAccessException e) {
            log.warn("Exception retrieving availability zone with id: {}",
                    zoneID, e);
            throw e;
        }

        builders.getZoneBuilder().setConfiguration(zone);
        return zone;
    }
}
