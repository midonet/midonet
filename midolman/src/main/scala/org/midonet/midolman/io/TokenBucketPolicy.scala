/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */

package org.midonet.midolman.io

import java.util.concurrent.ConcurrentHashMap

import org.midonet.midolman.config.MidolmanConfig
import org.midonet.util.{TokenBucketFillRate, StatisticalCounter, TokenBucket}
import org.midonet.odp.DpPort
import org.midonet.odp.ports.{VxLanTunnelPort, GreTunnelPort}

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

    private val root = TokenBucket.create(config.getGlobalIncomingBurstCapacity,
                                          tbRate)
    private val vmBuckets = root.link(0)

    private val tokenBuckets = new ConcurrentHashMap[DpPort, TokenBucket]

    def link(port: DpPort): TokenBucket = {
        val tb = port match {
            case p: GreTunnelPort =>
                root.link(config.getTunnelIncomingBurstCapacity)
            case p: VxLanTunnelPort =>
                root.link(config.getVtepIncomingBurstCapacity)
            case p =>
                vmBuckets.link(config.getVmIncomingBurstCapacity)
        }

        tokenBuckets.putIfAbsent(port, tb) match {
            case null => tb
            case prev => prev
        }
    }

    def unlink(port: DpPort): Unit = {
        tokenBuckets.remove(port) match {
            case null =>
            case tb if tunnel(port) => root.unlink(tb)
            case tb => vmBuckets.unlink(tb)
        }
    }
}
