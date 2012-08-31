/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.midonet.cluster;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.inject.Inject;
import javax.inject.Named;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.midolman.guice.zookeeper.ZKConnectionProvider;
import com.midokura.midolman.host.state.HostDirectory;
import com.midokura.midolman.host.state.HostZkManager;
import com.midokura.midolman.state.Directory;
import com.midokura.midolman.state.StateAccessException;
import com.midokura.midolman.state.ZkDirectory;
import com.midokura.midolman.state.zkManagers.AvailabilityZoneZkManager;
import com.midokura.midolman.state.zkManagers.BgpZkManager;
import com.midokura.midonet.cluster.client.AvailabilityZones;
import com.midokura.midonet.cluster.client.BridgeBuilder;
import com.midokura.midonet.cluster.client.ChainBuilder;
import com.midokura.midonet.cluster.client.HostBuilder;
import com.midokura.midonet.cluster.client.PortBuilder;
import com.midokura.midonet.cluster.client.RouterBuilder;
import com.midokura.midonet.cluster.data.AvailabilityZone;
import com.midokura.midonet.cluster.data.zones.GreAvailabilityZone;
import com.midokura.midonet.cluster.data.zones.GreAvailabilityZoneHost;
import com.midokura.util.eventloop.Reactor;
import static com.midokura.midonet.cluster.client.AvailabilityZones.GreBuilder;

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
    HostZkManager hostManager;

    @Inject
    BgpZkManager bgpZkManager;

    @Inject
    AvailabilityZoneZkManager aZoneZkManager;

    @Inject
    ClusterRouterManager routerManager;

    @Inject
    ClusterBridgeManager bridgeManager;

    @Inject
    ClusterPortsManager portsManager;

    /**
     * We inject it because we want to use the same {@link Reactor} as {@link ZkDirectory}
     */
    @Inject
    @Named(ZKConnectionProvider.DIRECTORY_REACTOR_TAG)
    Reactor reactorLoop;

    @Override
    public void getBridge(UUID bridgeID, BridgeBuilder builder) {
        // asynchronous call, we will process it later
        try {
            bridgeManager.registerNewBuilder(bridgeID, builder);
        } catch (ClusterClientException e) {
            //TODO(ross) what should be sent back in case of error?
        }
        reactorLoop.submit(
            bridgeManager.getConfig(bridgeID));
        log.info("getBridge {}", bridgeID);
    }

    @Override
    public void getRouter(UUID routerID, RouterBuilder builder) {
        try {
            routerManager.registerNewBuilder(routerID, builder);
        } catch (ClusterClientException e) {
            //TODO(ross) what should be send back in case of error?
        }
        reactorLoop.submit(
            routerManager.getConfig(routerID));
        log.info("getRouter {}", routerID);
    }

    @Override
    public void getChain(UUID chainID, ChainBuilder builder) {
    }

    @Override
    public void getPort(UUID portID, PortBuilder builder) {

        try {
            portsManager.registerNewBuilder(portID, builder);
        } catch (ClusterClientException e) {
            //TODO(ross) what should be send back in case of error?
        }
        reactorLoop.submit(portsManager.getConfig(portID));
    }

    @Override
    public void getHost(final UUID hostID, final HostBuilder builder) {
        reactorLoop.submit(new Runnable() {
            @Override
            public void run() {
                getHostConfig(hostID, builder, false);
            }
        });
    }

    @Override
    public void getAvailabilityZone(final UUID zoneID, AvailabilityZones.BuildersProvider builders) {

        AvailabilityZone<?, ?> zone = readAvailabilityZone(zoneID, builders);

        readHosts(zone, new HashMap<UUID, AvailabilityZone.HostConfig<?, ?>>(),
                  builders);
    }

    private void readHosts(final AvailabilityZone<?, ?> zone,
                           final Map<UUID, AvailabilityZone.HostConfig<?, ?>> zoneHosts,
                           final AvailabilityZones.BuildersProvider builders) {

        try {
            Set<UUID> currentList =
                aZoneZkManager.getZoneMemberships(zone.getId(),
                                                  new Directory.DefaultTypedWatcher() {
                                                      @Override
                                                      public void pathChildrenUpdated(String path) {
                                                          readHosts(
                                                              zone,
                                                              zoneHosts,
                                                              builders);
                                                      }
                                                  });

            Set<AvailabilityZone.HostConfig<?, ?>> newMemberships =
                new HashSet<AvailabilityZone.HostConfig<?, ?>>();

            for (UUID uuid : currentList) {
                if (!zoneHosts.containsKey(uuid)) {
                    newMemberships.add(
                        aZoneZkManager.getZoneMembership(
                            zone.getId(), uuid, null)
                    );
                }
            }

            Set<UUID> removedMemberships = new HashSet<UUID>();
            for (UUID uuid : zoneHosts.keySet()) {
                if (!currentList.contains(uuid)) {
                    removedMemberships.add(uuid);
                }
            }

            for (AvailabilityZone.HostConfig<?, ?> newHost : newMemberships) {
                triggerZoneMembershipChange(zone, newHost, builders, true);
                zoneHosts.put(newHost.getId(), newHost);
            }

            for (UUID removedHost : removedMemberships) {
                triggerZoneMembershipChange(zone, zoneHosts.get(removedHost),
                                            builders, false);
                zoneHosts.remove(removedHost);
            }

        } catch (StateAccessException e) {
            log.error("Exception while reading hosts", e);
        }
    }

    private void triggerZoneMembershipChange(AvailabilityZone<?, ?> zone,
                                             AvailabilityZone.HostConfig<?, ?> hostConfig,
                                             AvailabilityZones.BuildersProvider buildersProvider,
                                             boolean added) {
        switch (zone.getType()) {
            case Gre:
                if (hostConfig instanceof GreAvailabilityZoneHost) {
                    GreAvailabilityZoneHost greConfig = (GreAvailabilityZoneHost) hostConfig;

                    if (added) {
                        buildersProvider
                            .getGreZoneBuilder()
                            .addHost(greConfig.getId(), greConfig);
                    } else {
                        buildersProvider
                            .getGreZoneBuilder()
                            .removeHost(greConfig.getId(), greConfig);
                    }
                }
                break;

            case Capwap:
            case Ipsec:
        }
    }

    private AvailabilityZone<?, ?> readAvailabilityZone(final UUID zoneID,
                                                        final AvailabilityZones.BuildersProvider builders) {

        try {
            AvailabilityZone<?, ?> zone =
                aZoneZkManager.getZone(
                    zoneID,
                    new Directory.DefaultTypedWatcher() {
                        @Override
                        public void pathDataChanged(String path) {
                            readAvailabilityZone(zoneID, builders);
                        }
                    });

            if (zone instanceof GreAvailabilityZone) {
                final GreAvailabilityZone greZone = (GreAvailabilityZone) zone;
                builders.getGreZoneBuilder()
                        .setConfiguration(
                            new GreBuilder.ZoneConfig() {
                                @Override
                                public GreAvailabilityZone getAvailabilityZoneConfig() {
                                    return greZone;
                                }
                            });
            }

            return zone;
        } catch (StateAccessException e) {
            log.error("Exception retrieving availability zone with id: {}",
                      zoneID, e);
            return null;
        }
    }

    private HostDirectory.Metadata retrieveHostMetadata(final UUID hostId,
                                                        final HostBuilder builder,
                                                        final boolean isUpdate) {
        try {
            HostDirectory.Metadata metadata =
                hostManager.getHostMetadata(
                    hostId, new Directory.DefaultTypedWatcher() {
                    @Override
                    public void pathDataChanged(String path) {
                        retrieveHostMetadata(hostId, builder, true);
                    }

                    @Override
                    public void pathDeleted(String path) {
//                        builder.deleted();
                    }
                });

            if (isUpdate)
                builder.build();

            return metadata;
        } catch (StateAccessException e) {
            log.error("Exception: ", e);
            // trigger delete if that's the case.
            return null;
        }
    }

    private String retrieveHostDatapathName(final UUID hostId,
                                            final HostBuilder builder,
                                            final boolean isUpdate) {
        try {
            String datapath =
                hostManager.getVirtualDatapathMapping(
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
            // trigger delete if that's the case.
            return null;
        }
    }

    private Set<HostDirectory.VirtualPortMapping>
    retrieveHostVirtualPortMappings(final UUID hostId,
                                    final HostBuilder builder,
                                    final Set<HostDirectory.VirtualPortMapping> oldMappings,
                                    boolean isUpdate) {
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
            //  So make sure we are consistent in teh face of intermittent
            //     failures to load a port mapping we will first install the
            //     oldMappings into the newMappings and only after we return
            //     successfully from the getVirtualPortMappings we have sent the
            //     updates to the callers
            final Set<HostDirectory.VirtualPortMapping> newMappings
                = hostManager
                .getVirtualPortMappings(
                    hostId, new Directory.DefaultTypedWatcher() {
                    @Override
                    public void pathChildrenUpdated(String path) {
                        retrieveHostVirtualPortMappings(
                            hostId, builder, oldMappings, true);
                    }
                });

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
        } catch (StateAccessException e) {
            // trigger delete if that's the case.
            return null;
        }
    }

    private void getHostConfig(final UUID hostId,
                               final HostBuilder builder,
                               final boolean isUpdate) {

        log.info("Updating host information for host {}", hostId);

        HostDirectory.Metadata metadata =
            retrieveHostMetadata(hostId, builder, isUpdate);

        if (metadata != null) {
            retrieveAvailabilityZoneConfigs(hostId, new HashSet<UUID>(), builder);

            retrieveHostDatapathName(hostId, builder, isUpdate);

            retrieveHostVirtualPortMappings(
                hostId, builder,
                new HashSet<HostDirectory.VirtualPortMapping>(),
                isUpdate);

            builder.build();
        }
    }

    private Map<UUID, AvailabilityZone.HostConfig<?, ?>>
    retrieveAvailabilityZoneConfigs(final UUID hostId,
                                    final Set<UUID> oldZones,
                                    final HostBuilder builder) {
        try {
            Map<UUID, AvailabilityZone.HostConfig<?, ?>> zoneConfigsForHost =
                new HashMap<UUID, AvailabilityZone.HostConfig<?, ?>>();

            Set<UUID> newZones =
                hostManager.getAvailabilityZoneIds(
                    hostId,
                    new Directory.DefaultTypedWatcher() {
                        @Override
                        public void pathDataChanged(String path) {
                            retrieveAvailabilityZoneConfigs(hostId, oldZones,
                                                            builder);
                        }
                    });

            for (UUID uuid : newZones) {
                zoneConfigsForHost.put(
                    uuid,
                    aZoneZkManager.getZoneMembership(uuid, hostId, null));
            }

            builder.setAvailabilityZones(zoneConfigsForHost);

            oldZones.clear();
            oldZones.addAll(newZones);

            return zoneConfigsForHost;
        } catch (StateAccessException e) {
            log.error("Exception", e);
            return null;
        }
    }
}
