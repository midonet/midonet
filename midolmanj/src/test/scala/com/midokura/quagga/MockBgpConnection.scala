/**
 * MockBgpConnection.scala - Mock of BgpConnection for configuring Quagga.
 *
 * Copyright (c) 2011 Midokura KK. All rights reserved.
 */

package com.midokura.quagga


import org.slf4j.LoggerFactory
import com.midokura.packets.IntIPv4


class MockBgpConnection extends BgpConnection {
    private final val log = LoggerFactory.getLogger(this.getClass)

    override def getAs: Int = {
        log.info("getAs")
        0
    }

    override def setAs(as: Int) {
        log.info("setAs")
    }

    override def deleteAs(as: Int) {
        log.info("deleteAs")
    }

    override def setLocalNw(as: Int, localAddr: IntIPv4) {
        log.info("setLocalNw")
    }

    override def setPeer(as: Int, peerAddr: IntIPv4, peerAs: Int) {
        log.info("setPeer")
    }

    override def deletePeer(as: Int, peerAddr: IntIPv4) {
        log.info("deletePeer")
    }

    override def getNetwork: Seq[String] = {
        log.info("getNetwork")
        Seq[String]()
    }

    override def setNetwork(as: Int, nwPrefix: String, prefixLength: Int) {
        log.info("setNetwork")
    }

    override def deleteNetwork(as: Int, nwPrefix: String, prefixLength: Int) {
        log.info("deleteNetwork")
    }
}
