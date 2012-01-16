/**
 * MockZebraServer.scala - Mock of Quagga Zebra server classes.
 *
 * Copyright (c) 2011 Midokura KK. All rights reserved.
 */

package com.midokura.midolman.quagga

import com.midokura.midolman.openvswitch.OpenvSwitchDatabaseConnection
import com.midokura.midolman.state.{PortZkManager, RouteZkManager}

import java.net.{ServerSocket, SocketAddress}

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
