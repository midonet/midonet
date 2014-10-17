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
import org.midonet.util.functors.Callback;

/**
 * This class orchestrates synchronisation across logical switch
 * components comprising a vtep and midonet bridges.
 */
public class VxLanGwBroker {

    private final static Logger log =
            LoggerFactory .getLogger(VxLanGwBroker.class);

    // The connection UUID
    private final java.util.UUID connectionId;

    // Vtep configuration store client provider
    private final VtepDataClientFactory vtepDataClientFactory;

    // VTEP Configuration store client
    private final VtepDataClient vtepClient;

    // VTEP peer
    public final VtepBroker vtepPeer;

    // Midonet peer
    public final MidoVxLanPeer midoPeer;

    private final Subscription midoSubscription;
    private final Subscription vtepSubscription;
    private final Subscription connectSubscription;

    /**
     * Error handler for each rx.Observable.
     */
    private final Action1<Throwable> errorHandler = new Action1<Throwable>() {
        @Override
        public void call(Throwable throwable) {
            log.error("Error on VxLanPeer update stream", throwable);
        }
    };

    /**
     * Finalizer for rx.Observables.
     */
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
                         final VTEP vtep,
                         TunnelZoneState tunnelZone,
                         UUID connectionId) throws VtepStateException {
        log.info("Setup VXGW broker for VTEP {}", vtep.getId());
        this.vtepDataClientFactory = vtepDataClientFactory;
        this.connectionId = connectionId;

        vtepClient = this.vtepDataClientFactory
            .connect(vtep.getId(), vtep.getMgmtPort(), connectionId);

        this.vtepPeer = new VtepBroker(this.vtepClient);
        this.midoPeer = new MidoVxLanPeer(midoClient);

        // Set a callback method for when the VTEP becomes connected.
        connectSubscription = vtepClient.onConnected(
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
                    } catch (StateAccessException | SerializationException e) {
                        log.error("Cannot clear logical switches for VTEP {}: "
                                  + "retrieving state failed.", client, e);
                    } catch (VtepNotConnectedException e) {
                        log.warn("Cannot clear logical switches for VTEP {}: "
                                 + "connectoin failed (retrying)", client);
                        client.onConnected(this);
                    }
                }

                @Override
                public void onTimeout() {}

                @Override
                public void onError(VtepException e) {
                    log.error("Connection to VTEP {} was disposed.", e.vtep);
                }
            });

        // Wire peers
        midoSubscription = wirePeers(midoPeer, vtepPeer);
        vtepSubscription = wirePeers(vtepPeer, midoPeer);

        // Subscribe to the tunnel zone flooding proxy.
        midoPeer.subscribeToFloodingProxy(
            tunnelZone.getFloodingProxyObservable());

        // Try and update the VTEP data.
        try {
            if (null == vtep.getTunnelIp()) {
                vtep.setTunnelIp(vtepClient.getTunnelIp());
                midoClient.vtepUpdate(vtep);
            }
        } catch (StateAccessException | SerializationException e) {
            log.warn("Failed to update VTEP configuration: {}", e.getMessage());
        }
    }

    /**
     * Clean up state.
     */
    public void terminate() throws VtepStateException {
        log.info("Terminating VXGW broker for VTEP {}", vtepClient);
        midoSubscription.unsubscribe();
        vtepSubscription.unsubscribe();
        connectSubscription.unsubscribe();
        vtepClient.disconnect(connectionId, true);
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
