/*
 * Copyright 2012 Midokura Pte. Ltd.
 */

package org.midonet.functional_test.utils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/*
* whatever comes out from one tap, it is received by the other
*/
public class TapProxy {

    private final static Logger log = LoggerFactory.getLogger(TapProxy.class);

    TapWrapper tap1;
    TapWrapper tap2;
    Boolean keepGoing;

    ExecutorService executorService;

    public TapProxy(TapWrapper tap1, TapWrapper tap2) {
        if (tap1 == null) {
            log.error("tap1 is null");
            throw new IllegalArgumentException("tap1 is null");
        }

        if (tap2 == null) {
            log.error("tap2 is null");
            throw new IllegalArgumentException("tap2 is null");
        }

        if (tap1 == tap2) {
            throw new IllegalArgumentException("the taps cannot be the same");

        }

        this.tap1 = tap1;
        this.tap2 = tap2;

        keepGoing = true;
        log.debug("TapProxy created for taps " + tap1.getName() + " and " + tap2.getName());
    }

    public void start() {

        log.debug("Starting tap proxy");

        /*
         * tap recv method does sleep if there's no packet, so only one thread
         * should be enough.
         */
        executorService = Executors.newFixedThreadPool(2);

        executorService.execute(new Runnable() {

            @Override
            public void run() {
                while (keepGoing) {
                    byte[] packet = tap1.recv();
                    if (packet == null) continue;
                    log.debug("received a packet from tap " + tap1.getName());
                    tap2.send(packet);
                }
            }
        });

        executorService.execute(new Runnable() {

            @Override
            public void run() {
                while (keepGoing) {
                    byte[] packet = tap2.recv();
                    if (packet == null) continue;
                    log.debug("received a packet from tap " + tap2.getName());
                    tap1.send(packet);
                }
            }
        });
    }

    public void stop() {
        log.debug("stopping tap proxy");
        keepGoing = false;
        executorService.shutdown();
        try {
            executorService.awaitTermination(2500, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            log.debug("Error stopping tap proxy threads.");
        }
    }

}
