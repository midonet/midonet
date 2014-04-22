/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */

package org.midonet.midolman.io

import java.util.concurrent.ConcurrentHashMap

import org.midonet.midolman.config.MidolmanConfig
import org.midonet.util.{TokenBucketFillRate, TokenBucket}
import org.midonet.odp.DpPort
import org.midonet.odp.ports.{VxLanTunnelPort, GreTunnelPort}
import org.slf4j.{Logger, LoggerFactory}

object TokenBucketPolicy {
    private def tunnel(port: DpPort): Boolean =
        port.isInstanceOf[GreTunnelPort] ||
        port.isInstanceOf[VxLanTunnelPort]
}

/**
 * This class contains a policy to assign token buckets to datapath ports.
 * Depending on the port type, token buckets are linked at different levels
 * in the hierarchy.
 */
class TokenBucketPolicy(config: MidolmanConfig,
                        tbRate: TokenBucketFillRate) {
    import TokenBucketPolicy.tunnel

    private val log: Logger = LoggerFactory.getLogger(classOf[TokenBucketPolicy])

    private val root = TokenBucket.create(config.getGlobalIncomingBurstCapacity,
                                          "midolman-root",
                                          tbRate)

    log.info("Creating root bucket with {} tokens",
             config.getGlobalIncomingBurstCapacity)

    private val vmBuckets = root.link(0, "vms")

    private val tokenBuckets = new ConcurrentHashMap[DpPort, TokenBucket]

    def link(port: DpPort): TokenBucket = {
        val tb = port match {
            case p: GreTunnelPort =>
                root.link(config.getTunnelIncomingBurstCapacity, port.getName)
            case p: VxLanTunnelPort =>
                root.link(config.getVtepIncomingBurstCapacity, port.getName)
            case p =>
                vmBuckets.link(config.getVmIncomingBurstCapacity, port.getName)
        }

        tokenBuckets.putIfAbsent(port, tb) match {
            case null => tb
            case prev =>
                doUnlink(tb, port)
                prev
        }
    }

    def unlink(port: DpPort): Unit =
        tokenBuckets.remove(port) match {
            case null =>
            case tb => doUnlink(tb, port)
        }

    private def doUnlink(tb: TokenBucket, port: DpPort): Unit =
        if (tunnel(port))
            root.unlink(tb)
        else
            vmBuckets.unlink(tb)
}
