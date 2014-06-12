/**
 * Copyright (c) 2014 Midokura Europe SARL, All Rights Reserved.
 */
package org.midonet.brain.services.vxgw;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import rx.Subscription;
import rx.functions.Action0;
import rx.functions.Action1;

/**
 * This class orchestrates synchronisation accross two VxGW peers.
 *
 * TODO: not sure this has to be a Thread.
 */
public class VxLanGwBroker extends Thread {

    private final static Logger log =
            LoggerFactory .getLogger(VxLanGwBroker.class);

    /**
     * Error handler for each rx.Observable.
     */
    private final Action1<Throwable> errorHandler = new Action1<Throwable>() {
        @Override
        public void call(Throwable throwable) {
            log.error("Error on VxLan Peer update stream", throwable);
        }
    };

    /**
     * Finalizer for rx.Observables.
     */
    private final Action0 completionHandler = new Action0() {
        @Override
        public void call() {
            log.info("VxLanPeer stream is completed, shutting down");
        }
    };

    private volatile boolean running = false;

    private final VxLanPeer left;
    private final VxLanPeer right;

    private Subscription rightSubscription;
    private Subscription leftSubscription;

    /**
     * Creates a new Broker between two peers. This could easily be generalised
     * for a set of peers, but we can keep things simple for now.
     */
    public VxLanGwBroker(final VxLanPeer left, final VxLanPeer right) {
        this.left = left;
        this.right = right;
    }

    /**
     * Makes `dst` react upon updates from `src`.
     */
    private Subscription wirePeers(final VxLanPeer src, final VxLanPeer dst) {
        // TODO: review the subscribeOn for both cases, right now everything
        // runs on the same thread.
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

    /* Protected to allow using from unit tests */
    void setupWiring() {
        rightSubscription = wirePeers(left, right);
        leftSubscription = wirePeers(right, left);
    }

    /**
     * Wires the two VxlanPeers so they start exchanging MACs.
     */
    @Override
    public void run() {

        running = true;
        setupWiring();

        log.info("VxLanGwBroker active");
        while (running) {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                log.error("Interrupted - shutting down..", e);
                shutdown();
            }
        }
    }

    /**
     * Stop syncing peers.
     */
    public void shutdown() {
        this.running = false;
        log.info("Stopping VxLan peer synchronization..");
        if (rightSubscription != null) {
            rightSubscription.unsubscribe();
        }
        if (leftSubscription != null) {
            leftSubscription.unsubscribe();
        }
    }

}
