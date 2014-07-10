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
import org.midonet.brain.southbound.midonet.MidoVxLanPeer;
import org.midonet.brain.southbound.vtep.VtepBroker;
import org.midonet.brain.southbound.vtep.VtepConstants;
import org.midonet.brain.southbound.vtep.VtepDataClient;
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

    // Client for each VTEP configuration store, indexed by management IP
    private Map<IPv4Addr, VtepDataClient> vtepClients = new HashMap<>();

    // VTEP Peer object for each VTEP, indexed by management IP
    private Map<IPv4Addr, VtepBroker> vtepPeers = new HashMap<>();

    // Midonet Peer object for each VTEP, indexed by management IP
    private Map<IPv4Addr, MidoVxLanPeer> midoPeers = new HashMap<>();

    // Index of VxlanGwBrokers for each VTEP
    private Map<IPv4Addr, VxLanGwBroker> vxlanGwBrokers = new HashMap<>();

    // Subscription to bridge update observables
    private Subscription bridgeSubscription;

    // Bridge monitor
    private BridgeMonitor bridgeMon = null;

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

        // Set-up bridge monitoring
        try {
            bridgeMon = new BridgeMonitor(this.midoClient, this.zkConnWatcher);
        } catch (DeviceMonitor.DeviceMonitorException e) {
            log.warn("Service failed");
            shutdown();
            notifyFailed(e);
            return;
        }

        // creation stream
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

        // updates stream
        Observable<UUID> updateStream =
            bridgeMon.getEntityObservable().map(
                new Func1<Bridge, UUID>() {
                    @Override
                    public UUID call(Bridge b) {
                        return b.getId();
                    }
                }
            );

        // Subscribe to combined stream
        bridgeSubscription = Observable.merge(creationStream, updateStream)
            .subscribe(new Action1<UUID>() {
                @Override
                public void call(UUID id) {
                    assignBridgeToPeer(id);
                }
            });

        // Make sure we get all the initial state
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
        if (bridgeSubscription != null) {
            bridgeSubscription.unsubscribe();
            bridgeSubscription = null;
        }
        bridgeMon = null;
        for(Map.Entry<IPv4Addr, VxLanGwBroker> e : vxlanGwBrokers.entrySet()) {
            log.info("Unsubscribing broker from VTEP: {}", e.getKey());
            e.getValue().shutdown();
            VtepDataClient cli = vtepClients.get(e.getKey());
            if (cli == null) {
                log.warn("No VTEP client found for broker, ip: {}", e.getKey());
            } else {
                log.info("Disconnecting client from VTEP: {}", e.getKey());
                cli.disconnect();
            }
        }
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
            IPv4Addr mgmtIp = vtep.getId();

            // Configure VxLan Peers
            VtepDataClient vtepClient = vtepDataClientProvider.get();
            VtepBroker vtepPeer = new VtepBroker(vtepClient);
            MidoVxLanPeer midoPeer = new MidoVxLanPeer(midoClient);

            // Wire them
            VxLanGwBroker vxGwBroker = new VxLanGwBroker(vtepPeer, midoPeer);
            vxGwBroker.start(); // TODO: do we want a thread per broker?

            // Now kick off the VTEP. Needs to be at this point so that we
            // can capture the initial set of updates with the existing contents
            // of the Mac tables.
            try {
                vtepClient.connect(mgmtIp, vtep.getMgmtPort());
                vxlanGwBrokers.put(mgmtIp, vxGwBroker);
                vtepClients.put(mgmtIp, vtepClient);
                vtepPeers.put(mgmtIp, vtepPeer);
                midoPeers.put(mgmtIp, midoPeer);
            } catch (Exception ex) {
                log.warn("Failed connecting to {}", mgmtIp, ex);
            }
        }

    }

    /**
     * Assign a bridge to a midonet. vxlan peer
     */
    private void assignBridgeToPeerInternal(final UUID bridgeId)
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
        MidoVxLanPeer peer = midoPeers.get(vtepIp);
        if (null == peer) {
            log.warn("Unknown VTEP with IP {}", vtepIp);
            return;
        }

        VtepBroker vtepPeer = vtepPeers.get(vtepIp);
        if (null == vtepPeer) {
            // Something very bad has happened if we are here...
            log.error("Missing VTEP broker for {}", vxLanPort.getMgmtIpAddr());
            return;
        }

        String lsName = VtepConstants.bridgeIdToLogicalSwitchName(bridgeId);
        if (!peer.knowsBridgeId(bridgeId)) {
            log.info("Consolidating VTEP configuration for Log. Switch {}",
                     lsName);
            org.opendaylight.ovsdb.lib.notation.UUID lsUuid =
                vtepPeer.ensureLogicalSwitchExists(lsName, vxLanPort.getVni());
            vtepPeer.renewBindings(
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
            log.debug("Choosing flooding proxy for {}", vtepIp);
            IPv4Addr fpIp = chooseFloodingProxy(vtepIp);
            if (fpIp == null) {
                log.warn("Could not find flooding proxy for vtep {}", vtepIp);
            } else {
                log.info("Flooding proxy for Log. Switch {}: {}", lsName, fpIp);
                vtepPeer.setFloodingProxy(lsName, fpIp);
            }
        }

        log.info("Monitoring bridge {} for mac updates", bridge.getId());
        if (peer.watch(bridge.getId())) {
            vtepPeer.advertiseMacs(); // Make sure initial state is in sync
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
