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
import java.util._

import com.typesafe.scalalogging.Logger
import org.midonet.midolman.DatapathState
import org.midonet.midolman.monitoring.metrics.PacketPipelineMetrics
import org.midonet.util.concurrent.NanoClock
import org.slf4j.LoggerFactory

import com.lmax.disruptor.{EventHandler, LifecycleAware}

import org.midonet.midolman.datapath.DisruptorDatapathChannel._
import org.midonet.midolman.simulation.PacketContext
import org.midonet.netlink._
import org.midonet.odp.flows.FlowAction
import org.midonet.odp._
import org.midonet.packets.FlowStateEthernet

trait StatePacketExecutor {
    val log: Logger

    /**
     * TODO: Use MTU
     */
    private val stateBuf = new Array[Byte](FlowStateEthernet.FLOW_STATE_MAX_PAYLOAD_LENGTH)
    private val udpShell: FlowStateEthernet = new FlowStateEthernet(stateBuf)
    private val statePacket = new Packet(udpShell, FlowMatches.fromEthernetPacket(udpShell), udpShell.length)

    def prepareStatePacket(message: Array[Byte], length: Int): Packet = {
        try {
            System.arraycopy(message, 0, stateBuf, 0, length)
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
}

sealed class PacketExecutor(dpState: DatapathState,
                            families: OvsNetlinkFamilies,
                            numHandlers: Int, index: Int,
                            channelFactory: NetlinkChannelFactory,
                            metrics: PacketPipelineMetrics)
    extends EventHandler[PacketContextHolder]
    with LifecycleAware with StatePacketExecutor {

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
                    maybeExecuteStatePacket(datapathId, context)
                    executePacket(datapathId, packet, actions)
                    val latency = NanoClock.DEFAULT.tick - packet.startTimeNanos
                    metrics.packetSimulated(latency.toInt)
                    metrics.packetsProcessed.mark()
                    context.log.debug(s"Executed packet")
                } catch { case t: Throwable =>
                    context.log.error(s"Failed to execute packet", t)
                }
            }
        }
    }

    private def maybeExecuteStatePacket(datapathId: Int, context: PacketContext): Unit = {
        val actions = context.stateActions
        if (actions.size > 0) {
            try {
                val statePacket = prepareStatePacket(context.stateMessage,
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
                              actions: ArrayList[FlowAction]): Unit =
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
        } catch { case t: Throwable =>
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
