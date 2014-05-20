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
import com.google.common.collect.Multimap;
import com.google.common.util.concurrent.AbstractService;
import com.google.inject.Inject;
import org.midonet.brain.southbound.midonet.MidoVxLanPeer;
import org.midonet.brain.southbound.vtep.VtepBroker;
import org.midonet.brain.southbound.vtep.VtepDataClient;
import org.midonet.brain.southbound.vtep.VtepDataClientImpl;
import org.midonet.cluster.DataClient;
import org.midonet.cluster.data.Bridge;
import org.midonet.cluster.data.VTEP;
import org.midonet.cluster.data.ports.VxLanPort;
import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.state.StateAccessException;
import org.midonet.packets.IPv4Addr;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A service to integrate a Midonet cloud with hardware VTEPs.
 */
public class VxLanGatewayService extends AbstractService {

    private static final Logger log =
        LoggerFactory.getLogger(VxLanGatewayService.class);

    // Client for Midonet configuration store
    private final DataClient midoClient;

    // Client for each VTEP configuration store, indexed by management IP
    private Map<IPv4Addr, VtepDataClient> vtepClients = new HashMap<>();

    // Index of HW VTEP management IP to all bridges bound to a port in it
    private Multimap<IPv4Addr, UUID> vtepToBridgeIds =
        ArrayListMultimap.create();

    // Index of VxlanGwBrokers for each VTEP
    private Map<IPv4Addr, VxLanGwBroker> vxlanGwBrokers = new HashMap<>();

    @Inject
    public VxLanGatewayService(DataClient midoClient) {
        this.midoClient = midoClient;
    }

    @Override
    protected void doStart() {
        log.info("Starting up..");

        log.debug("Loading and connecting to VTEPs");
        try {
            startVtepClients();
            log.info("Connected to {}", vtepClients.keySet());
        } catch (SerializationException | StateAccessException e) {
            failStartup("Could not retrieve vtep clients", e);
            return;
        }

        log.debug("Loading bridges");
        try {
            loadBridges();
        } catch (SerializationException | StateAccessException e) {
            failStartup("Could not load bridges", e);
            return;
        }

        log.debug("Starting VxGW Brokers");
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
     * During initialisation, creates and tries to connect a new VtepDataClient
     * for each VTEP, populating the internal vtepClients map with all succesful
     * ones.
     */
    private void startVtepClients()
            throws SerializationException, StateAccessException {
        List<VTEP> vteps = midoClient.vtepsGetAll();
        vtepClients = new HashMap<>(vteps.size());
        for (VTEP vtep : vteps) {
            log.info("Initializing client for vtep: {}", vtep);
            // TODO: use a Guice Provider to chose implementation.
            try {
                VtepDataClient vdc = new VtepDataClientImpl();
                vdc.connect(vtep.getId(), vtep.getMgmtPort());
                vtepClients.put(vtep.getId(), vdc);
            } catch(Exception e) {
                log.warn("Failed connecting to {}", vtep);
            }
        }
    }

    /**
     * During initialisation, loads all the bridges with a vxlan port
     * configured. It will just read the vxlan bound bridges once, and not
     * monitor for changes resulting from new bindings or removals. This will
     * be implemented in the future.
     */
    private void loadBridges()
            throws SerializationException, StateAccessException {
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
                log.warn("Was given bridge {} with no vxlan port", b);
                continue;
            }
            IPv4Addr hwVtepIp = vxlanPort.getMgmtIpAddr();
            if (!vtepClients.containsKey(hwVtepIp)) {
                log.warn("Bridge {} bound to non configured or inaccessible" +
                         "VTEP {}, ignoring", b.getId(), hwVtepIp);
                continue;
            }

            vtepToBridgeIds.put(hwVtepIp, b.getId());
            log.debug("Bridge {} bound to Vtep {}", b.getId(), hwVtepIp);
        }
    }

    /**
     * Initialises the processes to synchronize data accross VxGW peers.
     */
    private void startVxGwBrokers() {
        for (Map.Entry<IPv4Addr, VtepDataClient> e : vtepClients.entrySet()) {
            IPv4Addr mgmtIp = e.getKey();
            VtepDataClient vtepClient = e.getValue();

            Collection<UUID> bridgeIds = vtepToBridgeIds.get(mgmtIp);
            MidoVxLanPeer midoPeer = new MidoVxLanPeer(midoClient);
            midoPeer.watch(bridgeIds);

            VtepBroker vtepPeer = new VtepBroker(vtepClient);
            VxLanGwBroker vxGwBroker = new VxLanGwBroker(vtepPeer, midoPeer);
            vxGwBroker.start(); // TODO: do we want a thread per broker?
            vxlanGwBrokers.put(mgmtIp, vxGwBroker);
        }
    }

}
