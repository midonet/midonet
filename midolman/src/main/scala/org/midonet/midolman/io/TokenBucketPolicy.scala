/*
 * Copyright 2014 Midokura SARL
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.midonet.midolman.io

import java.util.concurrent.locks.ReentrantLock

import scala.collection.mutable

import org.slf4j.{Logger, LoggerFactory}

import org.midonet.midolman.config.MidolmanConfig
import org.midonet.odp.DpPort
import org.midonet.util.{Bucket, TokenBucketFillRate, TokenBucket}

/**
 * This class contains a policy to assign token buckets to datapath ports.
 * Depending on the port type, token buckets are linked at different levels
 * in the hierarchy.
 */
class TokenBucketPolicy(config: MidolmanConfig,
                        tbRate: TokenBucketFillRate,
                        multiplier: Int,
                        factory: TokenBucket => Bucket) {
    private val log: Logger = LoggerFactory.getLogger("org.midonet.io.htb")

    private val root = TokenBucket.create(adjust(config.datapath.globalIncomingBurstCapacity),
                                          "midolman-root",
                                          tbRate)
    root addTokens root.getCapacity

    log.info("Creating root bucket with {} tokens", root.getCapacity)

    private val vmBuckets = root.link(0, "vms")

    private val tokenBuckets = mutable.Map[String, Bucket]()
    private val lock = new ReentrantLock

    def calculateMinimumSystemTokens: Int =
        tokenBuckets.foldLeft(0)(_ + _._2.underlyingTokenBucket().getCapacity)

    def link(port: DpPort, t: ChannelType): Bucket = {
        lock.lock()
        try {
            if (tokenBuckets.contains(port.getName)) {
                return tokenBuckets(port.getName)
            }

            val tb = factory(t match {
                case OverlayTunnel if config.datapath.tunnelIncomingBurstCapacity > 0 =>
                    root.link(adjust(config.datapath.tunnelIncomingBurstCapacity), port.getName)
                case VtepTunnel if config.datapath.vtepIncomingBurstCapacity > 0 =>
                    root.link(adjust(config.datapath.vtepIncomingBurstCapacity), port.getName)
                case VirtualMachine if config.datapath.vmIncomingBurstCapacity > 0 =>
                    vmBuckets.link(adjust(config.datapath.vmIncomingBurstCapacity), port.getName)
                case _ =>
                    return null
            })

            tokenBuckets.put(port.getName, tb)
            val curMax = root.getCapacity
            val newMax = calculateMinimumSystemTokens
            if (newMax > curMax) {
                root.setCapacity(newMax)
                root.addTokens(newMax - curMax)
            }

            log.info("HTB updated")
            root.dumpToLog()
            tb
        } finally {
            lock.unlock()
        }
    }

    def unlink(port: DpPort): Unit = {
        lock.lock()
        try {
            tokenBuckets.remove(port.getName) match {
                case Some(tb) =>
                    val tokens = tb.underlyingTokenBucket().unlink()
                    val newMax = calculateMinimumSystemTokens
                    if (newMax >= adjust(config.datapath.globalIncomingBurstCapacity))
                        root.setCapacity(newMax)
                    else
                        root.addTokens(tokens)

                    log.info("HTB updated")
                    root.dumpToLog()
                case None =>
                    log.warn("Port was deleted but not found in HTB: {}", port.getName)
            }
        } finally {
            lock.unlock()
        }
    }

    private def adjust(tokens: Int): Int = Math.max(1, tokens / multiplier)
}
