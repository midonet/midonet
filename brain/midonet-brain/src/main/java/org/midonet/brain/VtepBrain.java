/**
 * Copyright (c) 2014 Midokura Europe SARL, All Rights Reserved.
 */
package org.midonet.brain;

import com.google.inject.Inject;

import org.midonet.brain.southbound.midonet.MidoVtep;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A Runnable that coordinates MAC-port mapping in MidoNet bridges and VTEPs.
 */
public class VtepBrain implements Runnable {
    private final static Logger log =
            LoggerFactory .getLogger(VtepBrain.class);
    @Inject
    MidoVtep midoVtep;

    public void run() {
        try {
            midoVtep.start();
        } catch (Exception e) {
            log.error("Error starting a MidoVtep: {}", e);
        }
        while (midoVtep.isRunning()) {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                log.info("VtepBrain was terminated: {}", e);
                midoVtep.stop();
            }
        }
    }

}
