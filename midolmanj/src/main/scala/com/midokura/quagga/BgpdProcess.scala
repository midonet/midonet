/*
 * Copyright 2012 Midokura Pte. Ltd.
 */

package com.midokura.quagga

import com.midokura.midolman.routingprotocols.RoutingHandler
import org.slf4j.LoggerFactory
import java.io.IOException

class BgpdProcess(routingHandler: RoutingHandler, vtyPortNumber: Int) {
    private final val log = LoggerFactory.getLogger(this.getClass)
    var bgpdProcess: Process = null

    def start() {
        log.debug("Starting bgpd process.")

        try {
            bgpdProcess = Runtime.getRuntime.exec("sudo /usr/lib/quagga/bgpd -P " + vtyPortNumber)
            routingHandler.BGPD_READY
        } catch {
            case e: IOException => log.error("Cannot start bgpd process.")
        }

        log.debug("bgpd process started.")
    }

    def stop() {
        log.debug("stopping bgpd process.")

        if (bgpdProcess != null)
            bgpdProcess.destroy()
        else
            log.warn("Couldn't kill bgpd (" + vtyPortNumber + ") because it wasn't started")

        log.debug("bgpd process stopped.")
    }
}

