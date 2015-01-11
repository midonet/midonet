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

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import rx.Subscription;
import rx.functions.Action0;
import rx.functions.Action1;

import org.midonet.brain.southbound.midonet.MidoVxLanPeer;
import org.midonet.brain.southbound.vtep.VtepBroker;
import org.midonet.brain.southbound.vtep.VtepDataClient;
import org.midonet.brain.southbound.vtep.VtepDataClientFactory;
import org.midonet.brain.southbound.vtep.VtepException;
import org.midonet.brain.southbound.vtep.VtepNotConnectedException;
import org.midonet.brain.southbound.vtep.VtepStateException;
import org.midonet.cluster.DataClient;
import org.midonet.cluster.data.VTEP;
import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.state.StateAccessException;
import org.midonet.packets.IPv4Addr;
import org.midonet.util.functors.Callback;

/**
 * This class orchestrates synchronisation across logical switch participants
 * comprising a vtep and midonet bridges.
 */
public class VxLanGwBroker {

    private final static Logger log =
            LoggerFactory .getLogger(VxLanGwBroker.class);

    // The connection UUID
    private final java.util.UUID connectionId;

    // VTEP Configuration store clients, peers, and conection subscriptions
    private final Map<IPv4Addr, VtepDataClient> vtepClients = new HashMap<>();
    public final Map<IPv4Addr, VtepBroker> vtepPeers = new HashMap<>();
    private final Map<IPv4Addr, Subscription> cnxnSubscriptions = new HashMap<>();

    private final Map<IPv4Addr, Subscription> midoSubscriptions = new HashMap<>();
    private final Map<IPv4Addr, Subscription> vtepSubscriptions = new HashMap<>();

    // Midonet peer
    public final MidoVxLanPeer midoPeer;

    // Error handler for each rx.Observable.
    private final Action1<Throwable> errorHandler = new Action1<Throwable>() {
        @Override
        public void call(Throwable throwable) {
            log.error("Error on VxLanPeer update stream", throwable);
        }
    };

    // Finalizer for rx.Observables.
    private final Action0 completionHandler = new Action0() {
        @Override
        public void call() {
            log.info("VxLanPeer stream is completed");
        }
    };

    /**
     * Creates a new Broker between a VTEP and the corresponding MidoNet peers,
     * once ready, it will immediately connect the VTEP client.
     */
    public VxLanGwBroker(final DataClient midoClient,
                         VtepDataClientFactory vtepDataClientFactory,
                         final List<VTEP> vteps,
                         TunnelZoneState tunnelZone,
                         UUID cnxnId) throws VtepStateException {
        connectionId = cnxnId;

        for (final VTEP vtep : vteps) {
            final VtepDataClient vtepClient = vtepDataClientFactory.connect(
                                                  vtep.getId(),
                                                  vtep.getMgmtPort(), cnxnId);
            final VtepBroker vtepPeer = new VtepBroker(vtepClient);

            vtepPeers.put(vtep.getId(), vtepPeer);
            vtepClients.put(vtep.getId(), vtepClient);

            // Set a callback method for when the VTEP becomes connected.
            Subscription cnxnSubscription = setCnxnSubscription(
                vtepClient, midoClient, vtep, vtepPeer);

            cnxnSubscriptions.put(vtep.getId(), cnxnSubscription);

        }

        midoPeer = new MidoVxLanPeer(midoClient);

        // Wire peers
        for (Map.Entry<IPv4Addr, VtepBroker> e : vtepPeers.entrySet()) {
            IPv4Addr vtepIp = e.getKey();
            VtepBroker vtepPeer = e.getValue();
            midoSubscriptions.put(vtepIp, wirePeers(midoPeer, vtepPeer));
            vtepSubscriptions.put(vtepIp, wirePeers(vtepPeer, midoPeer));
        }

        // TODO: wire vteps to each other

        // Subscribe to the tunnel zone flooding proxy.
        midoPeer.subscribeToFloodingProxy(tunnelZone
                                              .getFloodingProxyObservable());

    }

    private Subscription setCnxnSubscription(
        final VtepDataClient vtepClient, final DataClient midoClient,
        final VTEP vtep, final VtepBroker vtepPeer) {
        return vtepClient.onConnected(
            new Callback<VtepDataClient, VtepException>() {
                @Override
                public void onSuccess(VtepDataClient client) {
                    // Remove the logical switches that do not have a network
                    // bound to them.
                    log.info("VXGW broker connected to VTEP {}", vtepClient);
                    try {
                        Collection<UUID> boundNetworks =
                            midoClient.bridgesBoundToVtep(vtep.getId());
                        vtepPeer.pruneUnwantedLogicalSwitches(boundNetworks);
                        try {
                            // Try and update the VTEP data.
                            if (null == vtep.getTunnelIp()) {
                                vtep.setTunnelIp(vtepClient.getTunnelIp());
                                midoClient.vtepUpdate(vtep);
                            }
                        } catch (StateAccessException | SerializationException e) {
                            log.warn("VTEP {}: config update failed", e.getMessage());
                        }
                    } catch (StateAccessException | SerializationException e) {
                        log.error("VTEP {}: retrieving state failed.", client, e);
                    } catch (VtepNotConnectedException e) {
                        log.warn("VTEP {}: connection failed (will retry)", client);
                        client.onConnected(this);
                    }
                }

                @Override
                public void onTimeout() {}

                @Override
                public void onError(VtepException e) {
                    log.error("VTEP {}: conection closed after error.", e.vtep);
                }
            });
    }

    /** Clean up state. */
    public void terminate() throws VtepStateException {
        for (Map.Entry<IPv4Addr, VtepDataClient> e : vtepClients.entrySet()) {
            IPv4Addr ip = e.getKey();
            VtepDataClient client = e.getValue();
            log.info("VTEP {}: disconnecting", client);
            for (Subscription s : midoSubscriptions.values()) {
                s.unsubscribe();
            }
            for (Subscription s : vtepSubscriptions.values()) {
                s.unsubscribe();
            }
            midoSubscriptions.clear();
            vtepSubscriptions.clear();
            Subscription s = cnxnSubscriptions.remove(ip);
            if (s != null) {
                s.unsubscribe();
            }
            client.disconnect(connectionId, true);
        }
        midoPeer.stop();
    }

    /**
     * Makes <code>dst</code> react upon updates from <code>src</code>.
     */
    private Subscription wirePeers(final VxLanPeer src, final VxLanPeer dst) {
        return src.observableUpdates()
                  .subscribe( // apply the update on the peer
                      new Action1<MacLocation>() {
                          @Override
                          public void call(MacLocation macLocation) {
                              log.debug("Apply {} to {}", macLocation, dst);
                              dst.apply(macLocation);
                          }
                      },
                      errorHandler,
                      completionHandler
                  );
    }
}
