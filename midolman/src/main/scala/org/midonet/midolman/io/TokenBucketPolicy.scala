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
import org.midonet.util.{Bucket, TokenBucketFillRate, TokenBucket}
import org.midonet.odp.DpPort
import org.midonet.odp.ports.{VxLanTunnelPort, GreTunnelPort}

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

    private val root = TokenBucket.create(adjust(config.getGlobalIncomingBurstCapacity),
                                          "midolman-root",
                                          tbRate)
    root addTokens root.getCapacity

    log.info("Creating root bucket with {} tokens", root.getCapacity)

    private val vmBuckets = root.link(0, "vms")

    private val tokenBuckets = mutable.Map[String, TokenBucket]()
    private val lock = new ReentrantLock

    def calculateMinimumSystemTokens: Int =
        tokenBuckets.foldLeft(0)(_ + _._2.getCapacity)

    def link(port: DpPort, t: ChannelType): Bucket = {
        val tb = t match {
            case OverlayTunnel if config.getTunnelIncomingBurstCapacity > 0 =>
                root.link(adjust(config.getTunnelIncomingBurstCapacity), port.getName)
            case VtepTunnel if config.getVtepIncomingBurstCapacity > 0 =>
                root.link(adjust(config.getVtepIncomingBurstCapacity), port.getName)
            case VirtualMachine if config.getVmIncomingBurstCapacity > 0 =>
                vmBuckets.link(adjust(config.getVmIncomingBurstCapacity), port.getName)
            case _ =>
                return null
        }

        lock.lock()
        try {
            tokenBuckets.put(port.getName, tb)
            val curMax = root.getCapacity
            val newMax = calculateMinimumSystemTokens
            if (newMax > curMax) {
                root.setCapacity(newMax)
                root.addTokens(newMax - curMax)
            }

            log.info("HTB updated")
            root.dumpToLog()
        } finally {
            lock.unlock()
        }

        factory(tb)
    }

    def unlink(port: DpPort): Unit = {
        lock.lock()
        try {
            tokenBuckets.remove(port.getName) match {
                case Some(tb) =>
                    val tokens = tb.unlink()
                    val newMax = calculateMinimumSystemTokens
                    if (newMax >= adjust(config.getGlobalIncomingBurstCapacity))
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
