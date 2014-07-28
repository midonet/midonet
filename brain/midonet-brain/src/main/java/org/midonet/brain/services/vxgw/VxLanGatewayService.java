/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */
package org.midonet.brain.services.vxgw;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import javax.annotation.Nullable;

import com.google.common.base.Predicate;
import com.google.common.collect.Collections2;
import com.google.common.util.concurrent.AbstractService;
import com.google.inject.Inject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import rx.Observable;
import rx.Subscription;
import rx.functions.Action1;
import rx.functions.Func1;

import org.midonet.brain.configuration.MidoBrainConfig;
import org.midonet.brain.services.vxgw.monitor.BridgeMonitor;
import org.midonet.brain.services.vxgw.monitor.DeviceMonitor;
import org.midonet.brain.southbound.vtep.VtepConstants;
import org.midonet.brain.southbound.vtep.VtepDataClientProvider;
import org.midonet.cluster.DataClient;
import org.midonet.cluster.EntityIdSetEvent;
import org.midonet.cluster.data.Bridge;
import org.midonet.cluster.data.VTEP;
import org.midonet.cluster.data.VtepBinding;
import org.midonet.cluster.data.ports.VxLanPort;
import org.midonet.config.HostIdGenerator;
import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.state.NoStatePathException;
import org.midonet.midolman.state.StateAccessException;
import org.midonet.midolman.state.ZookeeperConnectionWatcher;
import org.midonet.packets.IPv4Addr;

/**
 * A service to integrate a Midonet cloud with hardware VTEPs.
 */
public class VxLanGatewayService extends AbstractService {

    private static final Logger log =
        LoggerFactory.getLogger(VxLanGatewayService.class);

    // Client for Midonet configuration store
    private final DataClient midoClient;

    // Zookeeper connection watcher
    private ZookeeperConnectionWatcher zkConnWatcher;

    // Provides vtep clients
    private final VtepDataClientProvider vtepDataClientProvider;

    // Index of VxlanGwBrokers for each VTEP
    private final Map<IPv4Addr, VxLanGwBroker> vxlanGwBrokers = new HashMap<>();

    // Monitors
    private BridgeMonitor bridgeMonitor = null;
    private VxLanVtepMonitor vtepMonitor = null;

    // Subscription observables
    private List<Subscription> subscriptions = new ArrayList<>();

    // Service identifier.
    private final UUID serviceId;

    public class VtepConfigurationException extends RuntimeException {
        private static final long serialVersionUID = -1;

        public VtepConfigurationException(Throwable cause) {
            super("Failed to load VTEP configurations from storage", cause);
        }
    }

    @Inject
    public VxLanGatewayService(DataClient midoClient,
                               VtepDataClientProvider vtepDataClientProvider,
                               ZookeeperConnectionWatcher zkConnWatcher,
                               MidoBrainConfig config) {
        this.midoClient = midoClient;
        this.zkConnWatcher = zkConnWatcher;
        this.vtepDataClientProvider = vtepDataClientProvider;

        // Set the service identifier.
        serviceId = HostIdGenerator.readHostId(config);
        log.info("The VXLAN gateway service identifier: {}", serviceId);
        try {
            HostIdGenerator.writeHostId(serviceId, config);
        } catch (HostIdGenerator.PropertiesFileNotWritableException e) {
            log.error("The VXLAN gateway service cannot write to the "
                      + "configuration file.", e);
        }
    }

    @Override
    protected void doStart() {
        log.info("Starting up..");

        try {
            vtepMonitor = new VxLanVtepMonitor(
                this.midoClient, this.zkConnWatcher, serviceId);
            bridgeMonitor = new BridgeMonitor(
                this.midoClient, this.zkConnWatcher);
        } catch (DeviceMonitor.DeviceMonitorException e) {
            log.warn("Service startup failed", e);
            shutdown();
            notifyFailed(e);
            return;
        }

        // VTEP subscription
        subscriptions.add(vtepMonitor.getAcquireObservable().subscribe(
            new Action1<VxLanVtep>() {
                @Override
                public void call(VxLanVtep vtep) {
                    acquireVtepPairing(vtep.vtepIp);
                }
            }));
        subscriptions.add(vtepMonitor.getReleaseObservable().subscribe(
            new Action1<VxLanVtep>() {
                @Override
                public void call(VxLanVtep vtep) {
                    releaseVtepPairing(vtep.vtepIp);
                }
            }));

        // Bridge creation stream
        Observable<UUID> creationStream =
            bridgeMonitor.getEntityIdSetObservable().concatMap(
                new Func1<EntityIdSetEvent<UUID>, Observable<UUID>>() {
                    @Override
                    public Observable<UUID> call(EntityIdSetEvent<UUID> ev) {
                        switch (ev.type) {
                            case STATE: return Observable.from(ev.value);
                            case CREATE: return Observable.from(ev.value);
                            default: return Observable.empty();
                        }
                    }
                }
            );

        // Bridge updates stream
        Observable<UUID> updateStream =
            bridgeMonitor.getEntityObservable().map(
                new Func1<Bridge, UUID>() {
                    @Override
                    public UUID call(Bridge b) {
                        return b.getId();
                    }
                }
            );

        // Subscribe to combined bridge stream
        subscriptions.add(Observable.merge(creationStream, updateStream)
            .subscribe(new Action1<UUID>() {
                @Override
                public void call(UUID id) {
                    assignBridgeToPeer(id);
                }
            }));

        // Make sure we get all the initial state
        vtepMonitor.notifyState();
        bridgeMonitor.notifyState();

        log.info("Service started");
        notifyStarted();
    }

    @Override
    protected void doStop() {
        log.info("Service stopped");
        shutdown();
        notifyStopped();
    }

    /* Cleanup service state */
    private synchronized void shutdown() {
        // Dispose the monitor: must call before un-subscribing.
        vtepMonitor.dispose();

        // Unsubscribe.
        for (Subscription subscription : subscriptions) {
            subscription.unsubscribe();
        }

        vtepMonitor = null;
        bridgeMonitor = null;

        // All gateway brokers should have been cleaned up when disposing the
        // VTEP monitor.
        assert(vxlanGwBrokers.isEmpty());
    }

    /**
     * Release VTEP pairing
     */
    private void releaseVtepPairing(IPv4Addr vtepIp) {
        log.info("Release VTEP {} by {}", vtepIp, serviceId);

        VxLanGwBroker broker = vxlanGwBrokers.remove(vtepIp);
        if (broker == null)
            return;
        broker.terminate();
    }

    /**
     * Prepare a VTEP pairing with a MidoNet VXLAN peer.
     */
    private synchronized void setVtepPairingInternal(final VTEP vtep)
        throws StateAccessException, SerializationException {
        log.debug("Starting VxGW pairing for VTEP {}", vtep.getId());
        IPv4Addr mgmtIp = vtep.getId();

        // Sanity check
        if (vxlanGwBrokers.containsKey(mgmtIp)) {
            log.warn("VxLan gateway broker already set up for {}", mgmtIp);
            return;
        }

        // Kick off VTEP and wire peers
        VxLanGwBroker vxGwBroker = new VxLanGwBroker(midoClient,
                                                     vtepDataClientProvider,
                                                     mgmtIp,
                                                     vtep.getMgmtPort());
        vxlanGwBrokers.put(mgmtIp, vxGwBroker);

        // Remove Logical Switches that don't have a network bound to them
        Collection<UUID> boundNetworks = midoClient.bridgesBoundToVtep(mgmtIp);
        vxGwBroker.vtepPeer.pruneUnwantedLogicalSwitches(boundNetworks);
    }

    /**
     * Handle storage exceptions fo bridge assigning.
     */
    private void setVtepPairing(final VTEP vtep) {
        try {
            setVtepPairingInternal(vtep);
        } catch (NoStatePathException e) {
            log.warn("No vtep {} in storage", vtep.getId(), e);
        } catch (StateAccessException e) {
            log.warn("Cannot retrieve vtep state {} from storage",
                     vtep.getId(), e);
            zkConnWatcher.handleError(
                String.format("VxLanGatewayService %s pairing vtep %s",
                              serviceId, vtep.getId()),
                new Runnable () {
                    @Override
                    public void run() {setVtepPairing(vtep);}
                },
                e);
        } catch (SerializationException e) {
            log.error("Failed to deserialize resource vtep state {}",
                      vtep.getId(), e);
        }
    }

    /**
     * Acquire the VTEP associated to a given management IP
     */
    private void acquireVtepPairing(final IPv4Addr vtepIp) {
        log.info("Acquire VTEP {} by {}", vtepIp, serviceId);

        try {
            VTEP vtep = midoClient.vtepGet(vtepIp);
            setVtepPairing(vtep);
        } catch (NoStatePathException e) {
            log.warn("No vtep {} in storage", vtepIp, e);
        } catch (StateAccessException e) {
            log.warn("Cannot retrieve vtep state {} from storage",
                     vtepIp, e);
            zkConnWatcher.handleError(
                String.format("VxLanGatewayService %s pairing vtep %s",
                              serviceId, vtepIp),
                new Runnable () {
                    @Override
                    public void run() {acquireVtepPairing(vtepIp);}
                },
                e);
        } catch (SerializationException e) {
            log.error("Failed to deserialize resource vtep state {}",
                      vtepIp, e);
        }
    }

    /**
     * Assign a bridge to a midonet vxlan peer
     */
    private synchronized void assignBridgeToPeerInternal(final UUID bridgeId)
        throws StateAccessException, SerializationException {
        Bridge bridge = midoClient.bridgesGet(bridgeId);
        if (bridge == null) {
            log.warn("Bridge {} not found", bridgeId);
            return;
        }

        UUID vxLanPortId = bridge.getVxLanPortId();
        if (vxLanPortId == null)
            return;

        VxLanPort vxLanPort = (VxLanPort)midoClient.portsGet(vxLanPortId);
        if (vxLanPort == null) {
            log.warn("Cannot retrieve vxlan port state {}", vxLanPortId);
            return;
        }

        IPv4Addr vtepIp = vxLanPort.getMgmtIpAddr();
        VxLanGwBroker broker = vxlanGwBrokers.get(vtepIp);
        if (broker == null) {
            // This probably means that the vtep does not belong to us
            log.info("Unmanaged VTEP with IP {}", vxLanPort.getMgmtIpAddr());
            return;
        }

        String lsName = VtepConstants.bridgeIdToLogicalSwitchName(bridgeId);
        if (!broker.midoPeer.knowsBridgeId(bridgeId)) {
            log.info("Consolidating VTEP conf. for newly managed Bridge {}",
                     bridgeId);
            org.opendaylight.ovsdb.lib.notation.UUID lsUuid =
                broker.vtepPeer.ensureLogicalSwitchExists(lsName,
                                                          vxLanPort.getVni());
            broker.vtepPeer.renewBindings(
                lsUuid,
                Collections2.filter(
                    midoClient.vtepGetBindings(vtepIp),
                    new Predicate<VtepBinding>() {
                        @Override
                        public boolean apply(@Nullable VtepBinding b) {
                            return b != null && bridgeId
                                                .equals(b.getNetworkId());
                        }
                    }
                )
            );
        }

        log.info("Monitoring bridge {} for mac updates", bridge.getId());
        if (broker.midoPeer.watch(bridge.getId()))
            broker.vtepPeer.advertiseMacs();
    }

    /**
     * Handle storage exceptions for bridge assigning.
     */
    private void assignBridgeToPeer(final UUID bridgeId) {
        try {
            assignBridgeToPeerInternal(bridgeId);
        } catch (NoStatePathException e) {
            log.warn("Resource {} does not exist in storage", bridgeId, e);
        } catch (StateAccessException e) {
            log.warn("Cannot retrieve resource state {} from storage",
                     bridgeId, e);
            zkConnWatcher.handleError(
                "VxLanGatewayService assigning vxlan bridge " + bridgeId,
                new Runnable() {
                    @Override
                    public void run() {
                        assignBridgeToPeer(bridgeId);
                    }
                },
                e
            );
        } catch (VxLanPeerConsolidationException e) {
            log.error("Failed to consolidate VTEP state {}", bridgeId, e);
        } catch (SerializationException e) {
            log.warn("Failed to deserialize resource state {}", bridgeId, e);
        }
    }

    /**
     * Gets the set of currently owned VTEPs (used for testing).
     * @return The set of VTEP IP addresses.
     */
    protected Set<IPv4Addr> getOwnedVteps() {
        return vxlanGwBrokers.keySet();
    }
}
