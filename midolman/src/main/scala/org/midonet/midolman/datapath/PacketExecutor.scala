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

import java.util._

import com.google.protobuf.{MessageLite, CodedOutputStream}

import com.typesafe.scalalogging.Logger
import org.midonet.midolman.simulation.PacketContext
import org.slf4j.LoggerFactory

import com.lmax.disruptor.{EventHandler, LifecycleAware}

import org.midonet.midolman.datapath.DisruptorDatapathChannel._
import org.midonet.netlink._
import org.midonet.odp.flows.FlowAction
import org.midonet.odp.{FlowMatches, Packet, OvsProtocol, OvsNetlinkFamilies}
import org.midonet.packets.FlowStateEthernet
import org.midonet.util.FixedArrayOutputStream

trait StatePacketExecutor {
    val log: Logger

    /**
     * TODO: Use MTU
     */
    private val stateBuf = new Array[Byte](FlowStateEthernet.FLOW_STATE_MAX_PAYLOAD_LENGTH)
    private val stream = new FixedArrayOutputStream(stateBuf)
    private val udpShell: FlowStateEthernet = new FlowStateEthernet(stateBuf)
    private val statePacket = new Packet(udpShell, FlowMatches.fromEthernetPacket(udpShell))

    def prepareStatePacket(message: MessageLite): Packet = {
        val messageSizeVariantLength = CodedOutputStream.computeRawVarint32Size(
            message.getSerializedSize)
        val messageLength = message.getSerializedSize + messageSizeVariantLength
        stream.reset()
        try {
            message.writeDelimitedTo(stream)
            udpShell.limit(messageLength)
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

sealed class PacketExecutor(families: OvsNetlinkFamilies,
                            numHandlers: Int, index: Int,
                            channelFactory: NetlinkChannelFactory)
    extends EventHandler[PacketContextHolder]
    with LifecycleAware with StatePacketExecutor {

    val log = Logger(LoggerFactory.getLogger(s"org.midonet.datapath.packet-executor-$index"))

    private val writeBuf = BytesUtil.instance.allocateDirect(4*1024)
    private val readBuf = BytesUtil.instance.allocateDirect(4*1024)
    private val channel = channelFactory.create(blocking = true)
    private val pid = channel.getLocalAddress.getPid

    {
        log.debug(s"Created channel with pid $pid")
    }

    private val protocol = new OvsProtocol(pid, families)

    private val writer = new NetlinkBlockingWriter(channel)
    private val reader = new NetlinkReader(channel)

    override def onEvent(event: PacketContextHolder, sequence: Long,
                         endOfBatch: Boolean): Unit =
        if (sequence % numHandlers == index) {
            val context = event.packetExecRef
            val actions = context.packetActions
            val packet = context.packet
            event.packetExecRef = null
            if (actions.size > 0 && packet.getReason != Packet.Reason.FlowActionUserspace) {
                try {
                    executeStatePacket(sequence, context, event.datapathId)
                    executePacket(event.datapathId, packet, actions)
                    log.debug(s"Executed packet #$sequence with ${context.origMatch}")
                } catch { case t: Throwable =>
                    log.error(s"Failed to execute packet #$sequence", t)
                }
            }
        }

    private def executeStatePacket(sequence: Long, context: PacketContext,
                                   datapathId: Int): Unit =
        if (context.stateMessage ne null) {
            try {
                val statePacket = prepareStatePacket(context.stateMessage)
                executePacket(datapathId, statePacket, context.stateActions)
            } finally {
                context.stateMessage = null
                context.stateActions.clear()
            }
            log.debug(s"Executed flow state message for packet #$sequence")
        }

    private def executePacket(datapathId: Int, packet: Packet,
                              actions: ArrayList[FlowAction]): Unit =
        try {
            protocol.preparePacketExecute(datapathId, packet, actions, writeBuf)
            writer.write(writeBuf)
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
