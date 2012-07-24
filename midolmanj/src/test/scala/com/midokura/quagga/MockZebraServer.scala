/**
 * MockZebraServer.scala - Mock of Quagga Zebra server classes.
 *
 * Copyright (c) 2011 Midokura KK. All rights reserved.
 */

package com.midokura.quagga

import org.slf4j.LoggerFactory

class MockZebraServer extends ZebraServer {
    private final val log = LoggerFactory.getLogger(this.getClass)
    private var run = false


    def start() {
        run = true
        log.info("start")
    }

    def stop() {
        log.info("stop")
        run = false
    }
}
