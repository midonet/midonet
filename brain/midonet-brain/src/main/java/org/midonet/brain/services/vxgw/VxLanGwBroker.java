/**
 * Copyright (c) 2014 Midokura Europe SARL, All Rights Reserved.
 */
package org.midonet.brain.services.vxgw;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import rx.Subscription;
import rx.functions.Action0;
import rx.functions.Action1;

import org.midonet.brain.southbound.midonet.MidoVxLanPeer;
import org.midonet.brain.southbound.vtep.VtepBroker;
import org.midonet.brain.southbound.vtep.VtepDataClient;
import org.midonet.brain.southbound.vtep.VtepDataClientProvider;
import org.midonet.cluster.DataClient;
import org.midonet.cluster.data.VTEP;
import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.state.StateAccessException;
import org.midonet.packets.IPv4Addr;

/**
 * This class orchestrates synchronisation across logical switch
 * components comprising a vtep and midonet bridges.
 */
public class VxLanGwBroker {

    private final static Logger log =
            LoggerFactory .getLogger(VxLanGwBroker.class);

    // VTEP Configuration store client
    private final VtepDataClient vtepClient;

    // VTEP peer
    public final VtepBroker vtepPeer;

    // Midonet peer
    public final MidoVxLanPeer midoPeer;

    // VTEP management IP address
    public final IPv4Addr vtepMgmtIp;

    private Subscription midoSubscription;
    private Subscription vtepSubscription;

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
    public VxLanGwBroker(DataClient midoClient,
                         VtepDataClientProvider vtepDataClientProvider,
                         VTEP vtep,
                         TunnelZoneState tunnelZone) {
        log.info("Wiring broker for VTEP: {}", vtep.getId());
        this.vtepClient = vtepDataClientProvider.get();
        this.vtepPeer = new VtepBroker(this.vtepClient);
        this.midoPeer = new MidoVxLanPeer(midoClient);
        this.vtepMgmtIp = vtep.getId();

        // Wire peers
        this.midoSubscription = wirePeers(midoPeer, vtepPeer);
        this.vtepSubscription = wirePeers(vtepPeer, midoPeer);

        // Connect to VTEP
        vtepClient.connect(vtep.getId(), vtep.getMgmtPort());

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
     * Clean up state
     */
    public void terminate() {
        log.info("Terminating broker for {}", vtepMgmtIp);
        midoSubscription.unsubscribe();
        vtepSubscription.unsubscribe();
        vtepClient.disconnect();
        midoPeer.stop();
    }

    /**
     * Makes `dst` react upon updates from `src`.
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
