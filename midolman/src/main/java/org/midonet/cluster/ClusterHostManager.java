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

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import javax.inject.Inject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.midonet.cluster.client.HostBuilder;
import org.midonet.cluster.data.TunnelZone;
import org.midonet.midolman.host.state.HostDirectory;
import org.midonet.midolman.host.state.HostZkManager;
import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.state.Directory;
import org.midonet.midolman.state.StateAccessException;
import org.midonet.midolman.state.zkManagers.TunnelZoneZkManager;

public class ClusterHostManager extends ClusterManager<HostBuilder> {

    @Inject
    HostZkManager hostMgr;

    @Inject
    TunnelZoneZkManager tunnelZoneZkManager;

    private static final Logger log =
         LoggerFactory.getLogger(ClusterHostManager.class);

    /**
     * Get the conf for a host.
     * @param id
     * @param isUpdate
     * @return
     */
    private void getHostConf(final UUID id, final boolean isUpdate) {
        log.debug("Updating configuration for host" + " {}", id);
        HostBuilder builder = getBuilder(id);

        if (builder == null) {
            log.error("Null builder for host {}", id.toString());
            return;
        }

        HostDirectory.Metadata metadata;
        try {
            metadata = retrieveHostMetadata(id, builder, isUpdate,
                                            new HostDataWatcher(id, builder));
        } catch (StateAccessException e) {
            Runnable retry = new Runnable() {
                @Override
                public void run() {
                    getHostConf(id, isUpdate);
                }
            };
            connectionWatcher.handleError(
                    "HostConfig:" + id.toString(), retry, e);
            return;
        } catch (SerializationException e) {
            log.error("Serialization error");
            return;
        }

        if (metadata != null && !isUpdate) {
            retrieveTunnelZoneConfigs(id, new HashSet<UUID>(), builder);

            retrieveHostDatapathName(id, builder, isUpdate);

            retrieveHostVirtualPortMappings(
                    id, builder,
                    new HashSet<HostDirectory.VirtualPortMapping>(),
                    isUpdate);

            retrieveLiveness(id, builder, false, new HostAliveWatcher(id, builder));

            builder.build();
        }
    }

    @Override
    protected void getConfig(UUID id) {
        getHostConf(id, false);
    }

    private void retrieveLiveness(UUID id, HostBuilder builder, boolean isUpdate, Runnable watcher) {
        try {
            builder.setAlive(hostMgr.isAlive(id, watcher));
            if (isUpdate)
                builder.build();
        } catch (StateAccessException e) {
            ClusterHostManager.this.connectionWatcher.handleError(
                    "host alive watcher: " + id, watcher, e);
        } catch (Exception e) {
            // There is not much to do for this type of
            // exception.
            log.error("Exception while checking host liveness: {} ", e);
        }
    }

    private HostDirectory.Metadata retrieveHostMetadata(final UUID hostId,
                final HostBuilder builder, final boolean isUpdate, HostDataWatcher watcher)
                throws StateAccessException, SerializationException {
        try {
            HostDirectory.Metadata metadata = hostMgr.get(hostId, watcher);

            if (isUpdate)
                builder.build();

            return metadata;
        } catch (StateAccessException e) {
            log.error("Exception: ", e);
            // trigger delete if that's the case.
            throw e;
        } catch (SerializationException e) {
            log.error("Serialization Exception: ", e);
            throw e;
        }
    }

    class HostAliveWatcher implements Runnable {
        final UUID id;
        final HostBuilder builder;

        HostAliveWatcher(UUID id, HostBuilder builder) {
            this.id = id;
            this.builder = builder;
        }

        @Override
        public void run() {
            retrieveLiveness(id, builder, true, this);
        }
    }


    class HostDataWatcher extends Directory.DefaultPersistentWatcher {
        final UUID id;
        final HostBuilder builder;

        HostDataWatcher(UUID id, HostBuilder builder) {
            super(ClusterHostManager.this.connectionWatcher);
            this.id = id;
            this.builder = builder;
        }

        @Override
        public void pathDataChanged(String path) {
            run();
        }

        @Override
        public void _run() throws StateAccessException {
            try {
                retrieveHostMetadata(id, builder, true, this);
            } catch (SerializationException e) {
                // There is not much to do for this type of
                // exception.
                log.error("Serialization exception.");
            }
        }

        @Override
        public String describe() {
            return "HostMetadata:" + id.toString();
        }

        @Override
        public void pathDeleted(String path) {
            //builder.deleted();
        }
    }

    private void retrieveTunnelZoneConfigs(final UUID hostId,
                                           final Set<UUID> oldZones,
                                           final HostBuilder builder) {
        Collection<UUID> newZones = null;

        try {
            newZones =
                    hostMgr.getTunnelZoneIds(
                            hostId,
                            new Directory.DefaultTypedWatcher() {
                                @Override
                                public void pathChildrenUpdated(String path) {
                                    retrieveTunnelZoneConfigs(hostId, oldZones,
                                            builder);
                                    builder.build();
                                }
                            });
        } catch (StateAccessException e) {
            log.error("Exception", e);
            Runnable retry = new Runnable() {
                @Override
                public void run() {
                    retrieveTunnelZoneConfigs(hostId, oldZones, builder);
                }
            };
            connectionWatcher.handleError(
                    "TunnelZoneConfigs:" + hostId.toString(), retry, e);
            return;
        }

        try {
            Map<UUID, TunnelZone.HostConfig> hostTunnelZones =
                    new HashMap<UUID, TunnelZone.HostConfig>();
            for (UUID uuid : newZones) {
                hostTunnelZones.put(
                        uuid,
                        tunnelZoneZkManager.getZoneMembership(uuid, hostId, null));
            }

            builder.setTunnelZones(hostTunnelZones);

            oldZones.clear();
            oldZones.addAll(newZones);

        } catch (StateAccessException e) {
            // XXX(guillermo) I don't see how this block could be retried
            // without racing with the watcher installed by the first try{}
            // block or blocking the reactor by retrying in a loop right here.
            // The latter solution would only apply for timeout errors, not
            // disconnections.
            //
            // For now, this error is left unhandled.
            log.error("Exception", e);
        } catch (SerializationException e) {
            // Ditto as above.
            //
            // For now, this error is left unhandled.
            log.error("Serialization Exception: ", e);
        }
    }

    private String retrieveHostDatapathName(final UUID hostId,
                                            final HostBuilder builder,
                                            final boolean isUpdate) {
        try {
            String datapath =
                    hostMgr.getVirtualDatapathMapping(
                            hostId, new Directory.DefaultTypedWatcher() {
                                @Override
                                public void pathDataChanged(String path) {
                                    retrieveHostDatapathName(hostId, builder, true);
                                }
                            });

            builder.setDatapathName(datapath);

            if (isUpdate)
                builder.build();

            return datapath;
        } catch (StateAccessException e) {
            log.error("Exception: ", e);
            // TODO trigger delete if that's the case.
            Runnable retry = new Runnable() {
                @Override
                public void run() {
                    retrieveHostDatapathName(hostId, builder, isUpdate);
                }
            };

            connectionWatcher.handleError(
                    "HostDatapathName" + hostId.toString(), retry, e);
            return null;
        } catch (SerializationException e) {
            log.error("Serialization Exception: ", e);
            return null;
        }
    }

    private Set<HostDirectory.VirtualPortMapping>
    retrieveHostVirtualPortMappings(final UUID hostId,
                                    final HostBuilder builder,
                                    final Set<HostDirectory.VirtualPortMapping> oldMappings,
                                    boolean isUpdate) {
        Set<HostDirectory.VirtualPortMapping> newMappings = null;
        try {
            // We are running in the same thread as the one which is going to
            // run the watcher below. This implies the following set of
            // constraints (and assumptions) on the execution flow:
            //   - we want to store the old mappings and pass (via the Watcher)
            //      to the next call so it can do the diff
            //   - the processing of this method is running on the same thread
            //      as the one that will call the watcher (which means the calls
            //      to this method triggered by a change in the list of port
            //      mappings will effectively be serialized)
            //   - the entry point of this method needs the old known state of
            //      the port mappings
            //   - the hostManager.getVirtualPortMappings() method can fail after
            //      installing the watcher and said watcher gets the newMappings
            //      reference which should contain the list of old mappings.
            //
            //  So make sure we are consistent in the face of intermittent
            //     failures to load a port mapping we will first install the
            //     oldMappings into the newMappings and only after we return
            //     successfully from the getVirtualPortMappings we have sent the
            //     updates to the callers
            newMappings = hostMgr.getVirtualPortMappings(hostId,
                            new Directory.DefaultTypedWatcher() {
                                @Override
                                public void pathChildrenUpdated(String path) {
                                    retrieveHostVirtualPortMappings(hostId, builder,
                                                                    oldMappings, true);
                                }
                            });
        } catch (StateAccessException e) {
            // TODO trigger delete if that's the case.

            Runnable retry = new Runnable() {
                @Override
                public void run() {
                    retrieveHostVirtualPortMappings(hostId, builder, oldMappings, true);
                }
            };
            connectionWatcher.handleError(
                    "HostVirtualPortMappings:" + hostId.toString(), retry, e);
            return null;
        } catch (SerializationException e) {
            // Not much you can do here, as bad data exists in ZK.
            log.error("Serialization error.");
            return null;
        }

        for (HostDirectory.VirtualPortMapping mapping : oldMappings) {
            if (!newMappings.contains(mapping)) {
                builder.delMaterializedPortMapping(
                        mapping.getVirtualPortId(),
                        mapping.getLocalDeviceName());
            }
        }
        for (HostDirectory.VirtualPortMapping mapping : newMappings) {
            if (!oldMappings.contains(mapping)) {
                builder.addMaterializedPortMapping(
                        mapping.getVirtualPortId(),
                        mapping.getLocalDeviceName()
                );
            }
        }

        if (isUpdate)
            builder.build();

        oldMappings.clear();
        oldMappings.addAll(newMappings);

        return newMappings;
    }

}
