/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */
package org.midonet.brain.services.vxgw;

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

import org.midonet.brain.services.vxgw.monitor.BridgeMonitor;
import org.midonet.brain.services.vxgw.monitor.DeviceMonitor;
import org.midonet.brain.services.vxgw.monitor.VtepMonitor;
import org.midonet.brain.southbound.vtep.VtepConstants;
import org.midonet.brain.southbound.vtep.VtepDataClientProvider;
import org.midonet.cluster.DataClient;
import org.midonet.cluster.EntityIdSetEvent;
import org.midonet.cluster.data.Bridge;
import org.midonet.cluster.data.TunnelZone;
import org.midonet.cluster.data.VTEP;
import org.midonet.cluster.data.VtepBinding;
import org.midonet.cluster.data.ports.VxLanPort;
import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.state.NoStatePathException;
import org.midonet.midolman.state.StateAccessException;
import org.midonet.midolman.state.ZookeeperConnectionWatcher;
import org.midonet.packets.IPv4Addr;
import org.midonet.packets.IntIPv4;

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

    // Subscription to bridge update observables
    private Subscription bridgeSubscription;

    // Bridge monitor
    private BridgeMonitor bridgeMon = null;

    // Subscription to vtep update observables
    private Subscription vtepSubscription;

    // Vtep monitor
    private VtepMonitor vtepMon = null;

    // Service id for ownerships
    private final UUID srvId = UUID.randomUUID();

    public class VtepConfigurationException extends RuntimeException {
        private static final long serialVersionUID = -1;

        public VtepConfigurationException(Throwable cause) {
            super("Failed to load VTEP configurations from storage", cause);
        }
    }

    @Inject
    public VxLanGatewayService(DataClient midoClient,
                               VtepDataClientProvider vtepDataClientProvider,
                               ZookeeperConnectionWatcher zkConnWatcher) {
        this.midoClient = midoClient;
        this.zkConnWatcher = zkConnWatcher;
        this.vtepDataClientProvider = vtepDataClientProvider;
    }

    @Override
    protected void doStart() {
        log.info("Starting up..");

        // Set-up vxlan peer pairings
        setVxLanPeers();

        try {
            vtepMon = new VtepMonitor(this.midoClient, this.zkConnWatcher);
            bridgeMon = new BridgeMonitor(this.midoClient, this.zkConnWatcher);
        } catch (DeviceMonitor.DeviceMonitorException e) {
            log.warn("Service failed");
            shutdown();
            notifyFailed(e);
            return;
        }

        // Vtep subscription
        vtepSubscription = vtepMon.getEntityIdSetObservable().subscribe(
            new Action1<EntityIdSetEvent<IPv4Addr>>() {
                @Override
                public void call(EntityIdSetEvent<IPv4Addr> ev) {
                    switch (ev.type) {
                        case DELETE:
                            releaseVtepPairing(ev.value);
                            break;
                        case CREATE:
                        case STATE:
                            acquireVtepPairing(ev.value);
                            break;
                    }
                }
            }
        );

        // Bridge creation stream
        Observable<UUID> creationStream =
            bridgeMon.getEntityIdSetObservable().concatMap(
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
            bridgeMon.getEntityObservable().map(
                new Func1<Bridge, UUID>() {
                    @Override
                    public UUID call(Bridge b) {
                        return b.getId();
                    }
                }
            );

        // Subscribe to combined bridge stream
        bridgeSubscription = Observable.merge(creationStream, updateStream)
            .subscribe(new Action1<UUID>() {
                @Override
                public void call(UUID id) {
                    assignBridgeToPeer(id);
                }
            });

        // Make sure we get all the initial state
        vtepMon.notifyState();
        bridgeMon.notifyState();

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
        if (vtepSubscription != null) {
            vtepSubscription.unsubscribe();
            vtepSubscription = null;
        }
        if (bridgeSubscription != null) {
            bridgeSubscription.unsubscribe();
            bridgeSubscription = null;
        }
        vtepMon = null;
        bridgeMon = null;
        for(Map.Entry<IPv4Addr, VxLanGwBroker> e : vxlanGwBrokers.entrySet()) {
            log.info("Unsubscribing broker from VTEP: {}", e.getKey());
            e.getValue().terminate();
        }
        vxlanGwBrokers.clear();
    }

    /**
     * Initialises the processes to synchronize data accross VxGW peers.
     */
    private void setVxLanPeers() {
        List<VTEP> vteps;
        try {
            vteps = midoClient.vtepsGetAll();
        } catch (StateAccessException | SerializationException e) {
            throw new VtepConfigurationException(e);
        }

        log.info("Configuring VxLan Peers");

        for (VTEP vtep : vteps) {
            setVtepPairing(vtep);
        }
    }

    /**
     * Release vtep pairing
     */
    private void releaseVtepPairing(IPv4Addr vtepIp) {
        VxLanGwBroker broker = vxlanGwBrokers.remove(vtepIp);
        if (broker == null)
            return;
        broker.terminate();
    }

    /**
     * Prepare a vtep pairing with a midonet vxlan peer.
     */
    private synchronized void setVtepPairingInternal(final VTEP vtep)
        throws StateAccessException, SerializationException {
        log.debug("Starting VxGW pairing for VTEP {}", vtep.getId());
        IPv4Addr mgmtIp = vtep.getId();

        // Try to get ownership
        UUID ownerId = midoClient.tryOwnVtep(mgmtIp, srvId);
        if (!srvId.equals(ownerId)) {
            log.debug("VxLanGatewayService {} ignoring VTEP {} owned by {}",
                      new Object[]{srvId, vtep.getId(), ownerId});
            return;
        }

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
        // TODO: in a future patch, find associated bridges and watch them
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
                              srvId, vtep.getId()),
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
                              srvId, vtepIp),
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
     * Assign a bridge to a midonet. vxlan peer
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
        if (broker.midoPeer.watch(bridge.getId())) {
            log.debug("Choosing flooding proxy for {}", vtepIp);
            IPv4Addr fpIp = chooseFloodingProxy(vtepIp);
            if (fpIp == null) {
                log.warn("Could not find flooding proxy for vtep {}", vtepIp);
            } else {
                log.info("Flooding proxy for Log. Switch {}: {}", lsName, fpIp);
                broker.vtepPeer.setFloodingProxy(lsName, fpIp);
            }
            broker.vtepPeer.advertiseMacs();
        }
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
     * Selects a random host as Flooding Proxy for the given VTEP.
     *
     * @param vtepIp the Management IP of the VTEP
     * @return the IP of the host that peers to the VTEP as Flooding Proxy
     * @throws SerializationException
     * @throws StateAccessException
     */
    private IPv4Addr chooseFloodingProxy(IPv4Addr vtepIp)
        throws SerializationException, StateAccessException {

        VTEP vtep = midoClient.vtepGet(vtepIp);
        if (vtep == null) {
            log.warn("VTEP {} not found while chosing flooding proxy", vtepIp);
            return null;
        }

        UUID tzId = vtep.getTunnelZoneId();
        Set<TunnelZone.HostConfig>
            hostsCfg = midoClient.tunnelZonesGetMemberships(tzId);
        for (TunnelZone.HostConfig hostCfg : hostsCfg) {
            IntIPv4 ip = hostCfg.getIp();
            if (ip != null && midoClient.hostsIsAlive(hostCfg.getId())) {
                return ip.toIPv4Addr();
            }
        }
        return null;
    }

}
