/**
 * MockBgpConnection.scala - Mock of BgpConnection for configuring Quagga.
 *
 * Copyright (c) 2011 Midokura KK. All rights reserved.
 */

package com.midokura.midolman.quagga

import com.midokura.midolman.state.BgpZkManager.BgpConfig

import java.net.InetAddress
import java.util.UUID

import org.slf4j.LoggerFactory


class MockBgpConnection extends BgpConnection {
    private final val log = LoggerFactory.getLogger(this.getClass)
    private var run = false


    override def create(localAddr: InetAddress, bgpUUID: UUID,
                        bgp: BgpConfig) {
        log.info("create")
    }

    override def getAs(): Int = {
        log.info("getAs")
        return 0
    }

    override def setAs(as: Int) {
        log.info("setAs")
    }

    override def deleteAs(as: Int) {
        log.info("deleteAs")
    }

    override def setLocalNw(as: Int, localAddr: InetAddress) {
        log.info("setLocalNw")
    }

    override def setPeer(as: Int, peerAddr: InetAddress, peerAs: Int) {
        log.info("setPeer")
    }

    override def getNetwork(): Seq[String] = {
        log.info("getNetwork")
        return Seq[String]()
    }

    override def setNetwork(as: Int, nwPrefix: String, prefixLength: Int) {
        log.info("setNetwork")
    }

    override def deleteNetwork(as: Int, nwPrefix: String, prefixLength: Int) {
        log.info("deleteNetwork")
    }
}
