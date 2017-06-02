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

package org.midonet.midolman.datapath

import java.nio.BufferOverflowException
import java.nio.channels.AsynchronousCloseException
import java.util.concurrent.TimeUnit
import java.util.{ArrayList => JArrayList}

import scala.annotation.tailrec
import scala.util.control.NonFatal

import com.lmax.disruptor.{EventHandler, LifecycleAware}
import com.typesafe.scalalogging.Logger

import org.slf4j.LoggerFactory

import org.midonet.midolman.DatapathState
import org.midonet.midolman.datapath.DisruptorDatapathChannel._
import org.midonet.midolman.monitoring.metrics.PacketExecutorMetrics
import org.midonet.midolman.simulation.PacketContext
import org.midonet.netlink._
import org.midonet.odp._
import org.midonet.odp.flows.FlowAction
import org.midonet.packets._
import org.midonet.util.concurrent.NanoClock

trait StatePacketExecutor {
    val log: Logger

    /**
     * TODO: Use MTU
     */
    private val stateBuf = new Array[Byte](FlowStateEthernet.FLOW_STATE_MAX_PAYLOAD_LENGTH)
    private val udpShell: FlowStateEthernet = new FlowStateEthernet(stateBuf)
    private val statePacket = new Packet(udpShell, FlowMatches.fromEthernetPacket(udpShell), udpShell.length)

    def prepareStatePacket(connectionHash: Int, message: Array[Byte], length: Int): Packet = {
        try {
            System.arraycopy(message, 0, stateBuf, 0, length)
            udpShell.setConnectionHash(connectionHash)
            udpShell.limit(length)
        } catch {
            case _: IndexOutOfBoundsException =>
                // TODO(guillermo) partition messages
                log.warn(s"Skipping state packet, too large: $message")
            case e: Throwable =>
                log.warn("Failed to write state packet due to", e)
        }
        statePacket
    }
}

object PacketExecutor {
    private val MAX_BUF_CAPACITY = 4 * 1024 * 1024

    private[datapath] def clampMss(ctx: PacketContext, log: Logger): Unit = {
        // Don't do MSS clamping on packet tunneled here from another Midolman
        // node, since the other node already did it if needed.
        if (ctx.inputPort != null) {
            try clampMss(ctx.packet.getEthernet, 0, log) catch {
                case ex: ArrayIndexOutOfBoundsException =>
                    log.debug(
                        "Could not parse TCP options for packet " + ctx.packet)
            }
        }
    }

    @tailrec
    private def clampMss(pkt: IPacket, wrapperSize: Int, log: Logger)
    : Unit = pkt match {
        case t: TCP if t.getFlag(TCP.Flag.Syn) && (t.getOptions ne null) =>
            var i = 0
            val opts = t.getOptions
            var j = opts.length
            while (i < opts.length && j > 0) {
                j -= 1 // defense against going infinite due to logic bomb
                val code = opts(i)
                i += 1
                if (code == TCP.OptionKind.END_OPTS.code) {
                    return
                } else if (code <= TCP.OptionKind.NOP.code) {
                    // NOP has no arguments.
                } else if (code == TCP.OptionKind.MSS.code) {
                    // Reduce MSS by total size of all wrappers other than the
                    // inner IP and Ethernet packets, which the MSS already
                    // accounts for.
                    val len = opts(i)
                    if (len != 4) {
                        log.debug("MSS length should be 4, was " + len)
                        return
                    }
                    i += 1
                    val mss = ((opts(i) << 8) | opts(i + 1) & 0xff).toShort
                    val eth = t.getParent.getParent
                    val newMss = mss - wrapperSize + eth.length() - pkt.length()
                    if (newMss != mss) {
                        log.debug(s"Reducing MSS from $mss to $newMss")
                        opts(i) = (newMss >> 8).toByte
                        opts(i + 1) = newMss.toByte
                        clearChecksums(t)
                    }
                    return
                } else {
                    // Don't care about other options, so just skip arguments.
                    val len = opts(i)
                    if (len < 2) {
                        // length is an optional field, but should exist for all
                        // option kinds other than END_OPTS and NOP. Minimum
                        // length is 2 (1 octet for kind + 1 octet for length)
                        log.debug(s"Invalid tcp option len($len)."
                                      + " Is the packet corrupt? Bailing")
                        return
                    }
                    i += len - 1
                }
            }
        case t: TCP => // Don't expect TCP nested in TCP.
        case _ =>
            if (pkt.getPayload != null) {
                val headerLen = pkt.length - pkt.getPayload.length
                clampMss(pkt.getPayload, wrapperSize + headerLen, log)
            }
    }

    @tailrec
    private def clearChecksums(pkt: IPacket): Unit = {
        if (pkt != null) {
            pkt match {
                case t: Transport => t.clearChecksum()
                case ip: IPv4 => ip.clearChecksum()
                case _ =>
            }
            clearChecksums(pkt.getParent)
        }
    }
}

sealed class PacketExecutor(dpState: DatapathState,
                            families: OvsNetlinkFamilies,
                            numHandlers: Int, index: Int,
                            channelFactory: NetlinkChannelFactory,
                            metrics: PacketExecutorMetrics)
    extends EventHandler[PacketContextHolder]
    with LifecycleAware with StatePacketExecutor {
    import PacketExecutor._

    val log = Logger(LoggerFactory.getLogger(s"packet-executor-$index"))

    private val datapathId = dpState.datapath.getIndex

    private var writeBuf = BytesUtil.instance.allocateDirect(64 * 1024)
    private val readBuf = BytesUtil.instance.allocateDirect(8 * 1024)
    private val channel = channelFactory.create(blocking = true)
    private val pid = channel.getLocalAddress.getPid

    {
        log.debug(s"Created channel with pid $pid")
    }

    private val protocol = new OvsProtocol(pid, families)

    private val writer = new NetlinkBlockingWriter(channel)
    private val reader = new NetlinkReader(channel)

    override def onEvent(event: PacketContextHolder, sequence: Long,
                         endOfBatch: Boolean): Unit = {
        val context = event.packetExecRef
        if (sequence % numHandlers == index) {
            event.packetExecRef = null
            val actions = context.packetActions
            val packet = context.packet
            if (actions.size > 0 && packet.getReason != Packet.Reason.FlowActionUserspace) {
                try {
                    clampMss(context, log)
                    maybeExecuteStatePacket(datapathId, context)
                    executePacket(datapathId, packet, actions)
                    val latency = NanoClock.DEFAULT.tick - packet.startTimeNanos
                    metrics.packetsExecuted.update(latency.toInt,
                                                   TimeUnit.NANOSECONDS)
                    context.log.debug(s"Executed packet")
                } catch { case t: Throwable =>
                    context.log.error(s"Failed to execute packet", t)
                }
            }
            context.setPacketProcessed()
        }
    }

    private def maybeExecuteStatePacket(datapathId: Int, context: PacketContext): Unit = {
        val actions = context.stateActions
        if (actions.size > 0) {
            try {
                val statePacket = prepareStatePacket(context.returnFlowHash,
                                                     context.stateMessage,
                                                     context.stateMessageLength)
                executePacket(datapathId, statePacket, actions)
                context.log.debug(s"Executed flow state message")
            } finally {
                context.stateMessageLength = 0
                context.stateActions.clear()
            }
        }
    }

    private def executePacket(datapathId: Int, packet: Packet,
                              actions: JArrayList[FlowAction]): Unit =
        try {
            protocol.preparePacketExecute(datapathId, packet, actions, writeBuf)
            writer.write(writeBuf)
        } catch { case e: BufferOverflowException =>
            val capacity = writeBuf.capacity()
            if (capacity >= PacketExecutor.MAX_BUF_CAPACITY)
                throw e
            val newCapacity = capacity * 2
            writeBuf = BytesUtil.instance.allocateDirect(newCapacity)
            log.debug(s"Increasing buffer size to $newCapacity")
            executePacket(datapathId, packet, actions)
        } finally {
            writeBuf.clear()
        }

    private def processError(): Unit =
        try {
           if (reader.read(readBuf) > 0) {
               readBuf.clear()
               log.warn("Unexpected answer to packet execution")
           }
        } catch {
            case e: AsynchronousCloseException =>
                log.info("Netlink channel closed while reading packet")
            case NonFatal(t) =>
                log.error("Unexpected error while executing packets", t)
        }

    val errorHandler = new Thread(s"packet-executor-error-handler-$index") {
        override def run(): Unit =
            try {
                while (channel.isOpen) {
                    processError()
                }
            } catch { case ignored: Throwable => }
        }

    override def onStart(): Unit = {
        errorHandler.setDaemon(true)
        errorHandler.start()
    }

    override def onShutdown(): Unit = {
        channel.close()
        errorHandler.interrupt()
    }
}
