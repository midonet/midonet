/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */

package org.midonet.midolman.io

import java.util.concurrent.locks.ReentrantLock
import scala.collection.mutable.Map

import org.slf4j.{Logger, LoggerFactory}

import org.midonet.midolman.config.MidolmanConfig
import org.midonet.util.{TokenBucketFillRate, TokenBucket}
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

    private val log: Logger = LoggerFactory.getLogger(classOf[TokenBucketPolicy])

    private val root = TokenBucket.create(config.getGlobalIncomingBurstCapacity,
                                          "midolman-root",
                                          tbRate)
    root.addTokens(config.getGlobalIncomingBurstCapacity)

    log.info("Creating root bucket with {} tokens",
             config.getGlobalIncomingBurstCapacity)

    private val vmBuckets = root.link(0, "vms")

    private val tokenBuckets: Map[String, TokenBucket] = Map.empty
    private val lock = new ReentrantLock

    def calculateMinimumSystemTokens: Int = {
        tokenBuckets.foldLeft(0)(_ + _._2.getMaxTokens) +
            root.getDistributabilityAllotment
    }

    def link(port: DpPort): TokenBucket = {
        val tb = port match {
            case p: GreTunnelPort if config.getTunnelIncomingBurstCapacity > 0 =>
                root.link(config.getTunnelIncomingBurstCapacity, port.getName)
            case p: VxLanTunnelPort if config.getVtepIncomingBurstCapacity > 0 =>
                root.link(config.getVtepIncomingBurstCapacity, port.getName)
            case p if config.getVmIncomingBurstCapacity > 0 =>
                vmBuckets.link(config.getVmIncomingBurstCapacity, port.getName)
            case _ =>
                return null
        }

        lock.lock()
        try {
            tokenBuckets.put(port.getName, tb)
            val curMax = root.getMaxTokens
            val newMax = calculateMinimumSystemTokens
            if (newMax > curMax) {
                root.setMaxTokens(newMax)
                root.addTokens(newMax - curMax)
            }

            log.info("HTB updated")
            root.dumpToLog()
        } finally {
            lock.unlock()
        }

        tb
    }

    def unlink(port: DpPort): Unit = {
        lock.lock()
        try {
            val curMax = root.getMaxTokens
            tokenBuckets.remove(port.getName) match {
                case Some(tb) =>
                    val tokens = (if (tunnel(port)) root else vmBuckets).unlink(tb)
                    val newMax = calculateMinimumSystemTokens
                    root.setMaxTokens(newMax)
                    if (tokens > (curMax - newMax))
                        root.addTokens(tokens - (curMax - newMax))
                    // FIXME - otherwise we are leaving too many packets in the system.
                    // handle that case
                    log.info("HTB updated")
                    root.dumpToLog()
                case None =>
                    log.info("Port was deleted but not found in HTB: {}", port.getName)
            }
        } finally {
            lock.unlock()
        }

    }
}
