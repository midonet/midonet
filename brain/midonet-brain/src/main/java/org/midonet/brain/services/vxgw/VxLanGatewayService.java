/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */
package org.midonet.brain.services.vxgw;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.Lists;
import com.google.common.collect.Multimap;
import com.google.common.util.concurrent.AbstractService;
import com.google.inject.Inject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.midonet.brain.southbound.midonet.MidoVxLanPeer;
import org.midonet.brain.southbound.vtep.VtepBroker;
import org.midonet.brain.southbound.vtep.VtepDataClient;
import org.midonet.brain.southbound.vtep.VtepDataClientProvider;
import org.midonet.cluster.DataClient;
import org.midonet.cluster.data.Bridge;
import org.midonet.cluster.data.VTEP;
import org.midonet.cluster.data.ports.VxLanPort;
import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.state.StateAccessException;
import org.midonet.packets.IPv4Addr;

/**
 * A service to integrate a Midonet cloud with hardware VTEPs.
 */
public class VxLanGatewayService extends AbstractService {

    private static final Logger log =
        LoggerFactory.getLogger(VxLanGatewayService.class);

    // Client for Midonet configuration store
    private final DataClient midoClient;

    // Provides vtep clients
    private final VtepDataClientProvider vtepDataClientProvider;

    // Client for each VTEP configuration store, indexed by management IP
    private Map<IPv4Addr, VtepDataClient> vtepClients = new HashMap<>();

    // Index of HW VTEP management IP to all bridges bound to a port in it
    private Multimap<IPv4Addr, UUID> vtepToBridgeIds =
        ArrayListMultimap.create();

    // Index of VxlanGwBrokers for each VTEP
    private Map<IPv4Addr, VxLanGwBroker> vxlanGwBrokers = new HashMap<>();

    public class VtepConfigurationException extends RuntimeException {
        private static final long serialVersionUID = -1;

        public VtepConfigurationException(Throwable cause) {
            super("Failed to load VTEP configurations from storage", cause);
        }
    }

    @Inject
    public VxLanGatewayService(DataClient midoClient,
                               VtepDataClientProvider vtepDataClientProvider) {
        this.midoClient = midoClient;
        this.vtepDataClientProvider = vtepDataClientProvider;
    }

    @Override
    protected void doStart() {
        log.info("Starting up..");

        try {
            loadBridges();
        } catch (SerializationException | StateAccessException e) {
            failStartup("Could not load bridges", e);
            return;
        }

        startVxGwBrokers();

        log.info("Service started");
        notifyStarted();
    }

    @Override
    protected void doStop() {
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
        notifyStopped();
    }

    private void failStartup(String msg, Throwable e) {
        log.error("Failed to start service: " + msg, e);
        notifyFailed(e);
        doStop();
    }

    /**
     * During initialisation, loads all the bridges with a vxlan port
     * configured. It will just read the vxlan bound bridges once, and not
     * monitor for changes resulting from new bindings or removals. This will
     * be implemented in the future.
     */
    private void loadBridges()
            throws SerializationException, StateAccessException {
        log.debug("Loading bridges");
        vtepToBridgeIds = ArrayListMultimap.create();
        for (Bridge b : midoClient.bridgesGetAllWithVxlanPort()) {
            VxLanPort vxlanPort;
            try {
                vxlanPort = (VxLanPort)midoClient.portsGet(b.getVxLanPortId());
            } catch (SerializationException | StateAccessException e) {
                log.warn("Could not load vxlan port from bridge {}", b);
                continue;
            }
            if (vxlanPort == null) {
                log.warn("Bridge {} has no vxlan port", b);
                continue;
            }
            IPv4Addr hwVtepIp = vxlanPort.getMgmtIpAddr();
            vtepToBridgeIds.put(hwVtepIp, b.getId());
            log.debug("Bridge {} bound to vtep {}", b.getId(), hwVtepIp);
        }
    }

    /**
     * Initialises the processes to synchronize data accross VxGW peers.
     */
    private void startVxGwBrokers() {
        List<VTEP> vteps;
        try {
            vteps = midoClient.vtepsGetAll();
        } catch (StateAccessException | SerializationException e) {
            throw new VtepConfigurationException(e);
        }

        log.info("Starting VxGW Brokers");

        Collection<UUID> expectedBridgeIds =
            Lists.newArrayList(vtepToBridgeIds.values());
        for (VTEP vtep : vteps) {

            log.debug("Starting VxGW broker for VTEP: {}", vtep.getId());
            IPv4Addr mgmtIp = vtep.getId();

            // Configure the VxlanPeers
            VtepDataClient vtepClient = vtepDataClientProvider.get();
            vtepClients.put(mgmtIp, vtepClient);

            Collection<UUID> bridgeIds = vtepToBridgeIds.get(mgmtIp);
            expectedBridgeIds.removeAll(bridgeIds);

            MidoVxLanPeer midoPeer = new MidoVxLanPeer(midoClient);
            VtepBroker vtepPeer = new VtepBroker(vtepClient);

            // Wire them
            VxLanGwBroker vxGwBroker = new VxLanGwBroker(vtepPeer, midoPeer);
            vxGwBroker.start(); // TODO: do we want a thread per broker?

            // Now kick off the VTEP. Needs to be at this point so that we
            // can capture the initial set of updates with the existing contents
            // of the Mac tables.
            try {
                vtepClient.connect(mgmtIp, vtep.getMgmtPort());
                vxlanGwBrokers.put(mgmtIp, vxGwBroker);
            } catch (Exception ex) {
                log.warn("Failed connecting to {}", mgmtIp, ex);
            }

            // The VTEP is already broadcasting its updates before the midoPeer
            // is watching bridge ids, so we might lose some MAC-port updates
            midoPeer.watch(bridgeIds);

            vtepPeer.advertiseMacs(); // Re-advertise from the VTEP
        }

        if (!expectedBridgeIds.isEmpty()) {
            log.warn("Some bridges were bound to unknown VTEPs: ",
                     expectedBridgeIds);
        }
    }

}
