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

package org.midonet.midolman.state

import java.lang.{Integer => JInt, Long => JLong}
import java.util.UUID
import java.util.concurrent.{ConcurrentHashMap, ThreadLocalRandom}
import java.util.concurrent.TimeUnit.{NANOSECONDS => NANOS, MILLISECONDS => MILLIS}

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._
import scala.util.Success

import org.midonet.midolman.NotYetException
import org.midonet.midolman.rules.NatTarget
import org.midonet.midolman.state.NatBlockAllocator.NoFreeNatBlocksException
import org.midonet.packets.NatState.NatBinding
import org.midonet.packets.{IPAddr, IPv4Addr}
import org.midonet.util.collection.Reducer
import org.midonet.util.concurrent.{NanoClock, TimedExpirationMap}
import org.midonet.util.concurrent.ExecutionContextOps
import org.midonet.util.logging.Logger

object NatLeaser {
    private val BLOCK_SIZE = NatBlock.BLOCK_SIZE // Guaranteed to be a power of 2
    private val BLOCK_MULT = Integer.numberOfTrailingZeros(BLOCK_SIZE)
    private val BLOCK_MASK = BLOCK_SIZE - 1
    val BLOCK_EXPIRATION = 5 minutes
    private val OBLITERATION_CYCLE = (1 minute).toNanos

    private def blend(ip: IPv4Addr, port: Int): Long =
        (ip.toInt.toLong << 32) | port

    def blockOf(port: Int) = port >> BLOCK_MULT

    def firstPortIn(block: Int) = block << BLOCK_MULT

    /**
     * This type represents a block of BLOCK_SIZE ports. Each port, identified
     * by the NatBlock's tpPortStart plus the offset given by the position in
     * the leasedPorts array, can be oversubscribed based on a unique number,
     * a combination of the destination IP and port.
     * It also holds a port index to enable round-robin allocation
     * of the ports in the block.
     */
    sealed class LeasedBlock(val block: NatBlock) {
        val leasedPorts = new Array[ConcurrentHashMap[JLong, AnyRef]](BLOCK_SIZE)
        var portIndex = ThreadLocalRandom.current().nextLong()

        {
            var i = 0
            while (i < BLOCK_SIZE) {
                leasedPorts(i) = new ConcurrentHashMap[JLong, AnyRef]
                i += 1
            }
        }
    }

    /**
     * This type is a TimedExpirationMap of port block indexes to LeasedBLocks,
     * where the first port in that block is given by the port index * BLOCK_SIZE.
     * The port block index is an integer in the set [0, 1023] for a BLOCK_SIZE
     * of 64.
     */
    object LeasedBlocks {
        def apply(log: Logger): LeasedBlocks =
            new TimedExpirationMap[JInt, LeasedBlock](log, _ => BLOCK_EXPIRATION)
    }
    type LeasedBlocks = TimedExpirationMap[JInt, LeasedBlock]

    /**
     * This type is a map a NatTarget IP addresses to leased port blocks of
     * BLOCK_SIZE each. Each IP address can have at most 1024 port blocks
     * leased, for a BLOCK_SIZE of 64.
     */
    type IpLeases = ConcurrentHashMap[IPAddr, LeasedBlocks]

    /**
     * This type is a map of device IDs to leased port blocks scoped by IP.
     */
    type DeviceLeases = ConcurrentHashMap[UUID, IpLeases]

    type OutstandingRequests = ConcurrentHashMap[NatRange, Future[NatBlock]]
    object NoNatBindingException extends Exception {
        override def fillInStackTrace(): Throwable = this
    }
}

/**
 * Allocates a particular NatBinding for an SNAT operation. NatBindings are
 * scoped by device and by NatTarget IP and they are oversubscribed based on
 * both destination IP and destination port.
 */
trait NatLeaser {
    import NatLeaser._

    val log: Logger
    val allocator: NatBlockAllocator
    val clock: NanoClock
    private val deviceLeases = new DeviceLeases
    private val outstandingBlockRequests = new OutstandingRequests

    private var lastObliterated = 0L

    /**
     *  Allocates a NatBinding for a particular device. We further scope the
     *  NatBinding by the destination IP and port. This method is optimized
     *  for the single NatTarget with single IP use-case.
     *  Thread-safe for concurrent callers.
     */
    @throws(classOf[NotYetException])
    def allocateNatBinding(deviceId: UUID,
                           destinationIp: IPv4Addr,
                           destinationPort: Int,
                           natTargets: Array[NatTarget]): NatBinding = {
        var i = 0
        val uniquefier = blend(destinationIp, destinationPort)
        while (i < natTargets.length) {
            val target = natTargets(i)
            var ip = target.nwStart
            while (ip <= target.nwEnd) {
                val leasedBlocks = getLeasedBlocks(deviceId, ip)
                val binding = allocateInPortRange(leasedBlocks, uniquefier, ip,
                                                  target.tpStart, target.tpEnd)
                if (binding ne null) {
                    return binding
                }
                ip = ip.next
            }
            i += 1
        }

        throw new NotYetException(
            fetchNatBlock(deviceId, natTargets, natTargets(0).nwStart, 0))
    }

    /**
     * Frees the specified NatBinding, scoped by device and pair IP. If this
     * was the last reference to that NatBinding, it becomes eligible for
     * expiration. Thread-safe for concurrent callers. We check if we own
     * the binding.
     */
    def freeNatBinding(deviceId: UUID,
                       destinationIp: IPv4Addr,
                       destinationPort: Int,
                       binding: NatBinding): Unit = {
        val ipLeases = deviceLeases.get(deviceId)
        if (ipLeases eq null)
            return

        val leasedBlocks = ipLeases.get(binding.networkAddress)
        if (leasedBlocks eq null)
            return

        val leasedBlock = leasedBlocks.unref(blockOf(binding.transportPort),
                                             MILLIS.convert(clock.tick, NANOS))
        if (leasedBlock ne null) {
            val portOffset = binding.transportPort - leasedBlock.block.tpPortStart
            val uniquefier = blend(destinationIp, destinationPort)
            leasedBlock.leasedPorts(portOffset).remove(uniquefier)
        }
    }

    val blockObliterator = new Reducer[JInt, LeasedBlock, NatBlockAllocator]() {
        override def apply(acc: NatBlockAllocator, key: JInt,
                           value: LeasedBlock): NatBlockAllocator = {
            val block = value.block
            log.debug("Releasing NAT block {}", block)
            acc.freeBlock(block)
            acc
        }
    }

    /**
     * Returns any expired NatBlocks to the underlying allocator.
     * Thread-safe for concurrent callers.
     */
    def obliterateUnusedBlocks(): Unit = {
        val now = clock.tick
        if (now - lastObliterated > OBLITERATION_CYCLE) {
            val itDevs = deviceLeases.values().iterator()
            while (itDevs.hasNext) {
                val itIps = itDevs.next().values().iterator()
                while (itIps.hasNext) {
                    itIps.next().obliterateIdleEntries(
                        MILLIS.convert(clock.tick, NANOS), allocator,
                        blockObliterator)
                }
            }

            lastObliterated = now
        }
    }

    private def allocateInPortRange(leasedBlocks: LeasedBlocks, uniquefier: Long,
                                    targetIp: IPv4Addr, targetPortStart: Int,
                                    targetPortEnd: Int): NatBinding = {
        var port = targetPortStart
        while (port <= targetPortEnd) {
            val block = blockOf(port)
            val firstPortInNextBlock = firstPortIn(block + 1)
            val leasedBlock = leasedBlocks.ref(block)
            if (leasedBlock ne null) {
                val endPort = Math.min(targetPortEnd, firstPortInNextBlock - 1)
                val binding = allocateInPortBlock(leasedBlock, uniquefier,
                                                  targetIp, port, endPort)
                if (binding ne null) {
                    return binding
                }

                leasedBlocks.unref(block, MILLIS.convert(clock.tick, NANOS))
            }
            port = firstPortInNextBlock
        }
        null
    }

    private def allocateInPortBlock(lease: LeasedBlock, uniquefier: Long,
                                    ip: IPv4Addr, tpStart: Int, tpEnd: Int)
    : NatBinding = {
        val index = lease.portIndex
        val firstPortInBlock = lease.block.tpPortStart
        var i = 0
        while (i < BLOCK_SIZE) { // Search all ports
            val portOffset = (index + i).toInt & BLOCK_MASK
            val port = firstPortInBlock + portOffset
            if (port >= tpStart && port <= tpEnd) {
                val sharedBinding = lease.leasedPorts(portOffset)
                if (sharedBinding.putIfAbsent(uniquefier, this) eq null) {
                    lease.portIndex += i + 31
                    return NatBinding(ip, port)
                }
            }
            i += 1
        }
        null
    }

    private def fetchNatBlock(deviceId: UUID, targets: Array[NatTarget],
                                targetIp: IPv4Addr, targetIndex: Int): Future[NatBlock] = {
        val target = targets(targetIndex)
        val range = new NatRange(deviceId, targetIp, target.tpStart, target.tpEnd)

        implicit val ec = ExecutionContext.callingThread

        val preexistingRequest = outstandingBlockRequests.get(range)
        if (preexistingRequest != null) {
            preexistingRequest
        } else {
            val newRequest = allocator.allocateBlockInRange(range) andThen {
                case Success(data) => registerNewBlock(data)
            } recoverWith {
                case NoFreeNatBlocksException =>
                    val nextIp = targetIp.next
                    if (nextIp <= targets(targetIndex).nwEnd) {
                        fetchNatBlock(deviceId, targets, nextIp, targetIndex)
                    } else if (targetIndex + 1 < targets.length) {
                        fetchNatBlock(deviceId, targets,
                                      targets(targetIndex + 1).nwStart,
                                      targetIndex + 1)
                    } else {
                        Future.failed(NoNatBindingException)
                    }
            }
            val oldRequest = outstandingBlockRequests.putIfAbsent(range,
                                                                  newRequest)
            // must set callback after adding in case
            // the future completes instantly
            newRequest.onComplete {
                case _ => outstandingBlockRequests.remove(range, newRequest)
            }

            if (oldRequest != null)
                oldRequest
            else
                newRequest
        }
    }

    private def registerNewBlock(block: NatBlock): Unit = {
        log.debug("Acquiring NAT block {}", block)
        val leasedBlocks = getLeasedBlocks(block.deviceId, block.ip)
        val leasedBlock = new LeasedBlock(block)
        leasedBlocks.putAndRef(block.blockIndex, leasedBlock)
        leasedBlocks.unref(block.blockIndex, MILLIS.convert(clock.tick, NANOS))
    }

    private def getLeasedBlocks(deviceId: UUID, targetIp: IPAddr): LeasedBlocks = {
        val ipLeases = getOrCreateDeviceLeases(deviceId)
        getOrCreateIpLeases(ipLeases, targetIp)
    }

    private def getOrCreateDeviceLeases(deviceId: UUID) = {
        var value = deviceLeases.get(deviceId)
        if (value eq null) {
            value = new IpLeases
            val cur = deviceLeases.putIfAbsent(deviceId, value)
            if (cur ne null)
                value = cur
        }
        value
    }

    private def getOrCreateIpLeases(ipLeases: IpLeases, targetIp: IPAddr) = {
        var value = ipLeases.get(targetIp)
        if (value eq null) {
            value = LeasedBlocks(log)
            val cur = ipLeases.putIfAbsent(targetIp, value)
            if (cur ne null)
                value = cur
        }
        value
    }
}
