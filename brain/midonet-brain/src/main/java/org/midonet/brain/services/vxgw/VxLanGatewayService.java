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
package org.midonet.brain.services.vxgw;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

import javax.annotation.Nonnull;

import com.google.common.base.Predicate;
import com.google.common.collect.Collections2;
import com.google.inject.Inject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import rx.Subscription;
import rx.functions.Action1;

import org.midonet.brain.ClusterMinion;
import org.midonet.brain.ClusterNode;
import org.midonet.brain.MinionConfig;
import org.midonet.brain.services.vxgw.monitor.BridgeMonitor;
import org.midonet.brain.services.vxgw.monitor.DeviceMonitor;
import org.midonet.brain.southbound.vtep.VtepConstants;
import org.midonet.brain.southbound.vtep.VtepDataClientFactory;
import org.midonet.brain.southbound.vtep.VtepNotConnectedException;
import org.midonet.brain.southbound.vtep.VtepStateException;
import org.midonet.cluster.DataClient;
import org.midonet.cluster.EntityIdSetEvent;
import org.midonet.cluster.data.Bridge;
import org.midonet.cluster.data.VTEP;
import org.midonet.cluster.data.VtepBinding;
import org.midonet.cluster.data.ports.VxLanPort;
import org.midonet.config.ConfigBool;
import org.midonet.config.ConfigGroup;
import org.midonet.config.ConfigString;
import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.state.NoStatePathException;
import org.midonet.midolman.state.StateAccessException;
import org.midonet.midolman.state.ZookeeperConnectionWatcher;
import org.midonet.packets.IPv4Addr;

/**
 * A service to integrate a Midonet cloud with hardware VTEPs.
 */
public class VxLanGatewayService extends ClusterMinion {

    private static final Logger log =
        LoggerFactory.getLogger(VxLanGatewayService.class);

    // Client for Midonet configuration store
    private final DataClient midoClient;

    // Zookeeper connection watcher
    private ZookeeperConnectionWatcher zkConnWatcher;

    // Provides vtep clients
    private final VtepDataClientFactory vtepDataClientFactory;

    // Index of VxlanGwBrokers for each VTEP
    private final Map<IPv4Addr, VxLanGwBroker> vxlanGwBrokers = new HashMap<>();

    // Monitors
    private HostStatePublisher hostMonitor = null;
    private TunnelZoneStatePublisher tunnelZoneMonitor = null;
    private VtepStatePublisher vtepMonitor = null;
    private BridgeMonitor bridgeMonitor = null;

    // Subscription observables
    private List<Subscription> subscriptions = new ArrayList<>();

    // Random number generator.
    private final Random random;

    // Cluster Node context
    private final ClusterNode.Context nodeCtx;

    @Inject
    public VxLanGatewayService(
        @Nonnull ClusterNode.Context nodeCtx,
        @Nonnull DataClient midoClient,
        @Nonnull VtepDataClientFactory vtepDataClientFactory,
        @Nonnull ZookeeperConnectionWatcher zkConnWatcher) {
        this(nodeCtx, midoClient, vtepDataClientFactory, zkConnWatcher, new Random());
    }

    public VxLanGatewayService(
        @Nonnull ClusterNode.Context nodeCtx,
        @Nonnull DataClient midoClient,
        @Nonnull VtepDataClientFactory vtepDataClientFactory,
        @Nonnull ZookeeperConnectionWatcher zkConnWatcher,
        @Nonnull Random random) {
        super(nodeCtx); // Scala-induced awkwardness
        this.nodeCtx = nodeCtx;
        this.midoClient = midoClient;
        this.zkConnWatcher = zkConnWatcher;
        this.vtepDataClientFactory = vtepDataClientFactory;
        this.random = random;
    }

    @Override
    protected void doStart() {
        log.info("Starting up the VXLAN gateway service");

        // Set-up monitoring
        try {
            hostMonitor = new HostStatePublisher(midoClient, zkConnWatcher);
            tunnelZoneMonitor = new TunnelZoneStatePublisher(midoClient,
                                                             zkConnWatcher,
                                                             hostMonitor,
                                                             random);
            vtepMonitor = new VtepStatePublisher(midoClient, zkConnWatcher,
                                                 nodeCtx.nodeId());
            bridgeMonitor = new BridgeMonitor(midoClient, zkConnWatcher);
        } catch (DeviceMonitor.DeviceMonitorException e) {
            log.error("Service failed: cannot set up the device monitors.", e);
            shutdown();
            notifyFailed(e);
            return;
        }

        // VTEP subscriptions
        subscriptions.add(vtepMonitor.getAcquireObservable().subscribe(
            new Action1<VtepState>() {
                @Override
                public void call(VtepState vtep) {
                    onVtepAcquired(vtep.vtepIp);
                }
            }));
        subscriptions.add(vtepMonitor.getReleaseObservable().subscribe(
            new Action1<VtepState>() {
                @Override
                public void call(VtepState vtep) {
                    onVtepReleased(vtep.vtepIp);
                }
            }));


        // Bridge subscriptions
        subscriptions.add(bridgeMonitor.getEntityIdSetObservable().subscribe(
            new Action1<EntityIdSetEvent<UUID>>() {
                @Override
                public void call(EntityIdSetEvent<UUID> event) {
                    switch (event.type) {
                        case CREATE:
                        case STATE:
                            onBridgeCreatedOrUpdated(null, event.value);
                            break;
                    }
                }
            }));
        subscriptions.add(bridgeMonitor.getEntityObservable().subscribe(
            new Action1<Bridge>() {
                @Override
                public void call(Bridge bridge) {
                    onBridgeCreatedOrUpdated(bridge, bridge.getId());
                }
            }));

        // Notify the entities initial state: first VTEPs and then bridges.
        vtepMonitor.notifyState();
        bridgeMonitor.notifyState();

        log.info("Service started");
        notifyStarted();
        this.awaitRunning();
    }

    @Override
    protected void doStop() {
        log.info("Service stopped");
        shutdown();
        notifyStopped();
    }

    /* Cleanup service state */
    private synchronized void shutdown() {
        // Dispose the monitor: must call before un-subscribing in order to
        // receive notifications for any clean-up.
        hostMonitor.dispose();
        tunnelZoneMonitor.dispose();
        vtepMonitor.dispose();

        // Un-subscribe.
        for (Subscription subscription : subscriptions) {
            if (!subscription.isUnsubscribed()) {
                subscription.unsubscribe();
            }
        }
        subscriptions.clear();

        hostMonitor = null;
        tunnelZoneMonitor = null;
        vtepMonitor = null;
        bridgeMonitor = null;

        // All gateway brokers should have been cleaned up when disposing the
        // VTEP monitor.
        assert(vxlanGwBrokers.isEmpty());
    }

    /**
     * Safe method called when the VXGW service acquires the ownership of a
     * VTEP.
     * @param vtepIp The VTEP management address.
     */
    private void onVtepAcquired(final IPv4Addr vtepIp) {
        try {
            onVtepCreatedUnsafe(midoClient.vtepGet(vtepIp));
        } catch (NoStatePathException e) {
            log.warn("VTEP {} does not exist in storage (ignoring)", vtepIp, e);
        } catch (StateAccessException e) {
            log.warn("Cannot retrieve the VTEP state {} from storage (retrying)",
                     vtepIp, e);
            zkConnWatcher.handleError(
                String.format("VxLanGatewayService %s pairing VTEP %s",
                              nodeCtx.nodeId(), vtepIp),
                new Runnable() {
                    @Override
                    public void run() {
                        onVtepAcquired(vtepIp);
                    }
                },
                e);
        } catch (SerializationException e) {
            log.error("Failed to deserialize resource VTEP state {} (aborting)",
                      vtepIp, e);
        } catch (VtepStateException e) {
            log.error("Failed to connect to VTEP {} because of inconsistent"
                      + "connection state (aborting)", e.vtep, e);
        }
    }

    /**
     * Unsafe method called when the VXGW service acquires the ownership of a
     * VTEP. The method creates the VXLAN gateway broker corresponding to a
     * VTEP, and pairs it with a MidoNet VXLAN peer.
     * @param vtep The VTEP.
     * @return The VXLAN gateway broker corresponding to this VTEP.
     */
    private synchronized VxLanGwBroker onVtepCreatedUnsafe(VTEP vtep)
        throws StateAccessException, SerializationException,
               VtepStateException {

        IPv4Addr mgmtIp = vtep.getId();
        log.debug("Starting VXLAN gateway pairing for VTEP {}", mgmtIp);

        // If a gateway broker already exists, do nothing.
        if (vxlanGwBrokers.containsKey(mgmtIp)) {
            log.debug("VXLAN gateway broker is already set up for VTEP {}",
                      mgmtIp);
            return null;
        }

        // Get the tunnel zone corresponding to this VTEP.
        TunnelZoneState tunnelZone =
            tunnelZoneMonitor.getOrTryCreate(vtep.getTunnelZoneId());
        if (null == tunnelZone) {
            // If cannot create the tunnel zone state for
            log.error("Cannot create tunnel zone {} state for VTEP {}. The "
                      + "VXLAN service will initialize the VTEP without a "
                      + "flooding proxy.",
                      vtep.getTunnelZoneId(), mgmtIp);
        }

        // Initialize the VTEP and wire peers.
        VxLanGwBroker vxGwBroker = new VxLanGwBroker(
            midoClient, vtepDataClientFactory, vtep,
            tunnelZone, UUID.randomUUID());
        vxlanGwBrokers.put(mgmtIp, vxGwBroker);

        return vxGwBroker;
    }

    /**
     * Handles the release of ownership of a VTEP, by terminating the gateway
     * broker associated to the VTEP.
     * @param vtepIp The VTEP management address.
     */
    private void onVtepReleased(IPv4Addr vtepIp) {
        log.info("Release VTEP {} by {}", vtepIp, nodeCtx.nodeId());
        VxLanGwBroker broker = vxlanGwBrokers.remove(vtepIp);
        if (broker != null) {
            try {
                broker.terminate();
            } catch (VtepStateException e) {
                log.warn("Broker for VTEP {} failed to disconnect gracefully.",
                         e.vtep);
            }
        }
    }

    /**
     * Safe method that handles the update of an existing bridge. Updates may
     * include the creation and deletion of bridge VXLAN ports.
     * @param bridge The bridge.
     * @param id The bridge identifier.
     */
    private void onBridgeCreatedOrUpdated(final Bridge bridge, final UUID id) {
        log.debug("Bridge created or updated: {}", id);

        try {
            onBridgeUpdatedUnsafe(
                null == bridge ? midoClient.bridgesGet(id) : bridge);
        } catch (NoStatePathException e) {
            log.warn("Bridge {} does not exist in storage (ignoring)",
                     id, e);
        } catch (StateAccessException e) {
            log.warn("Cannot retrieve bridge state {} from storage (retrying)",
                     id, e);
            zkConnWatcher.handleError(
                String.format("VxLanGatewayService %s assigning bridge %s",
                              nodeCtx.nodeId(), id),
                new Runnable() {
                    @Override
                    public void run() {
                        onBridgeCreatedOrUpdated(bridge, id);
                    }
                }, e);
        } catch (VxLanPeerConsolidationException e) {
            log.error("Failed to consolidate VTEP state {} (aborting)",
                      id, e);
        } catch (SerializationException e) {
            log.error("Failed to deserialize resource state {} (aborting)",
                      id, e);
        } catch (VtepNotConnectedException e) {
            log.error("Failed to consolidate VTEP {} state for bridge {} "
                      + "because VTEP is not connected", e.vtep, id);
        }
    }

    /**
     * Handles the creation and update of a bridge, by assigning the bridge
     * to the corresponding MidoNet VXLAN peer.
     * @param bridge The bridge.
     */
    private void onBridgeUpdatedUnsafe(Bridge bridge)
        throws StateAccessException, SerializationException,
               VxLanPeerConsolidationException, VtepNotConnectedException {

        final UUID id = bridge.getId();

        // Get the VXLAN port identifier for this bridge.
        List<UUID> vxlanPortIds = bridge.getVxLanPortIds();
        if (vxlanPortIds == null || vxlanPortIds.isEmpty()) {
            log.debug("Bridge {} does not any port towards VTEP, ignoring", id);
            return;
        }

        UUID vxLanPortId = vxlanPortIds.get(0);
        VxLanPort vxLanPort = (VxLanPort)midoClient.portsGet(vxLanPortId);
        if (null == vxLanPort) {
            log.warn("Cannot retrieve VXLAN port for port ID {} (aborting)",
                     vxLanPortId);
            return;
        }

        IPv4Addr vtepIp = vxLanPort.getMgmtIpAddr();
        VxLanGwBroker broker = vxlanGwBrokers.get(vtepIp);
        if (null == broker) {
            log.info("Unknown or unowned VXLAN broker for VTEP {} (aborting)",
                     vtepIp);
            return;
        }

        if (!broker.midoPeer.knowsBridgeId(id)) {
            log.info("Consolidating VTEP configuration for new bridge "
                     + "{}", id);
            String lsName = VtepConstants.bridgeIdToLogicalSwitchName(id);
            int vni = vxLanPort.getVni();
            org.opendaylight.ovsdb.lib.notation.UUID lsUuid =
                broker.vtepPeer.ensureLogicalSwitchExists(lsName, vni);

            broker.vtepPeer.renewBindings(
                lsUuid,
                Collections2.filter(
                    midoClient.vtepGetBindings(vtepIp),
                    new Predicate<VtepBinding>() {
                        @Override
                        public boolean apply(VtepBinding b) {
                            return b != null && id.equals(b.getNetworkId());
                        }
                    }
                )
            );
        }

        log.info("Monitoring bridge {} for mac updates", id);
        if (broker.midoPeer.watch(id)) {
            broker.vtepPeer.advertiseMacs();
            broker.midoPeer.advertiseFloodingProxy(id);
        }
    }

    /**
     * Returns the host identifier that is designated as the current flooding
     * proxy for the specified tunnel zone (used for testing).
     * @param tzoneId The tunnel zone identifier.
     * @return The host identifier, or null if the tunnel zone does not exist,
     * or the tunnel zone does not have a flooding proxy.
     */
    protected UUID getFloodingProxy(UUID tzoneId) {
        TunnelZoneState tunnelZone = tunnelZoneMonitor.get(tzoneId);
        return (null != tunnelZone && tunnelZone.hasFloodingProxy()) ?
               tunnelZone.getFloodingProxy().id : null;
    }

    /**
     * Gets the set of currently owned VTEPs (used for testing).
     * @return The set of VTEP IP addresses.
     */
    protected Set<IPv4Addr> getOwnedVteps() {
        return vxlanGwBrokers.keySet();
    }

    /** Configuration for the VxGWService Minion. */
    @ConfigGroup(VxGWServiceConfig.configGroup)
    public interface VxGWServiceConfig extends MinionConfig<VxLanGatewayService> {

        public static final String configGroup = "vxgw";

        @ConfigBool(key = "enabled", defaultValue = false)
        public boolean isEnabled();

        @ConfigString(key = "with")
        public String minionClass();
    }
}
