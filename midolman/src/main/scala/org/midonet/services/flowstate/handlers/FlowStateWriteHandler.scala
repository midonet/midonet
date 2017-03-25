/*
 * Copyright 2016 Midokura SARL
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
package org.midonet.services.flowstate.handlers

import java.nio.ByteBuffer
import java.nio.file.FileSystemException
import java.util
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

import scala.collection.mutable.MutableList
import scala.util.control.NonFatal

import com.datastax.driver.core.Session
import com.google.common.annotations.VisibleForTesting

import org.midonet.cluster.storage.FlowStateStorage
import org.midonet.packets.ConnTrackState.ConnTrackKeyStore
import org.midonet.packets.FlowStateStorePackets._
import org.midonet.packets.NatState.{NatBinding, NatKeyStore}
import org.midonet.packets.SbeEncoder
import org.midonet.services.FlowStateLog
import org.midonet.services.flowstate.stream.{Context, FlowStateWriter}
import org.midonet.services.flowstate.{FlowStateInternalMessageHeaderSize, FlowStateInternalMessageType, MaxMessageSize}
import org.midonet.util.logging.Logging

import io.netty.channel.ChannelHandler.Sharable
import io.netty.channel.socket.DatagramPacket
import io.netty.channel.{ChannelHandlerContext, SimpleChannelInboundHandler}

private[flowstate] final class PerThreadContext(
        val storage: Option[FlowStateStorage[ConnTrackKeyStore, NatKeyStore]]) {

    private val headerBuff = ByteBuffer.allocate(FlowStateInternalMessageHeaderSize)
    private val bodyBuff = ByteBuffer.allocate(MaxMessageSize)
    private val portsSet = new util.ArrayList[UUID]()

    val encoder = new SbeEncoder()

    def header(): ByteBuffer = {
        headerBuff.clear()
        headerBuff
    }

    def body(): ByteBuffer = {
        bodyBuff.clear()
        bodyBuff
    }

    def matchingPorts(): util.ArrayList[UUID] = {
        portsSet.clear()
        portsSet
    }

}

/** Handler used to receive, parse and submit flow state messages from agents
  * to a local file and, if a legacy flag is active, also to the Cassandra
  * cluster. We reuse this handler for each incoming connection to avoid
  * garbage collection. */
@Sharable
class FlowStateWriteHandler(context: Context,
                            session: Session)
    extends SimpleChannelInboundHandler[DatagramPacket] with Logging {

    override def logSource = FlowStateLog
    override def logMark = "FlowStateWriteHandler"

    private val legacyPushState = context.config.legacyPushState
    private val localPushState = context.config.localPushState

    /**
      * Thread context private copy. Necessary as the FlowStateStorage
      * implementation is not thread safe. To overcome this limitation, we use
      * a local thread context with private copies of the FlowStateStorageImpl
      * object. This context also contains other data structures that don't
      * need to be allocated on each message received, thus reducing GCs.
      */
    protected[flowstate] val contextProvider: ThreadLocal[PerThreadContext] =
        new ThreadLocal[PerThreadContext] {
            override def initialValue(): PerThreadContext = {
                log debug "Getting the initial value for the flow state thread cache."
                val storage = if (legacyPushState) {
                    getLegacyStorage
                } else {
                    None
                }

                new PerThreadContext(storage)
            }
        }

    protected[flowstate] val portWriters = new ConcurrentHashMap[UUID, FlowStateWriter]()

    @volatile
    protected[flowstate] var cachedOwnedPortIds: Set[UUID] = Set.empty[UUID]

    override def channelRead0(ctx: ChannelHandlerContext,
                              msg: DatagramPacket): Unit = {
        try {
            val context = contextProvider.get
            val header = context.header()
            val body = context.body()
            msg.content.getBytes(0, header)
            header.flip()
            val messageType = header.getInt
            val messageSize = header.getInt
            body.limit(messageSize)
            msg.content.getBytes(FlowStateInternalMessageHeaderSize, body)
            body.flip()
            messageType match {
                case FlowStateInternalMessageType.FlowStateMessage =>
                    handleFlowStateMessage(body)
                case FlowStateInternalMessageType.OwnedPortsUpdate =>
                    handleUpdateOwnedPorts(body)
                case _ =>
                    log warn s"Invalid flow state message header, ignoring."
            }
        } catch {
            case NonFatal(e) =>
                log.error(s"Unkown error handling internal flow state message", e)
        }
    }

    private def handleFlowStateMessage(buffer: ByteBuffer): Unit = {
        maybeWriteInLegacyStorage(buffer)
        if (localPushState) {
            writeInLocalStorage(buffer)
        }
    }

    private def handleUpdateOwnedPorts(buffer: ByteBuffer): Unit = {
        var ownedPorts = Set.empty[UUID]
        while (buffer.position < buffer.limit()) {
            ownedPorts += new UUID(buffer.getLong, buffer.getLong)
        }
        log debug s"Received new owned ports: $ownedPorts"
        // check the difference to release the writers
        val unboundPorts = cachedOwnedPortIds -- ownedPorts
        for (unboundPort <- unboundPorts) {
            context.ioManager.close(unboundPort)
        }
        cachedOwnedPortIds = ownedPorts
    }

    @VisibleForTesting
    protected def getLegacyStorage
    : Option[FlowStateStorage[ConnTrackKeyStore, NatKeyStore]] =
        Some(FlowStateStorage[ConnTrackKeyStore, NatKeyStore](
            session, NatKeyStore, ConnTrackKeyStore))

    @throws[FileSystemException]
    protected[flowstate] def getFlowStateWriter(portId: UUID) =
        context.ioManager.stateWriter(portId)

    protected[flowstate] def maybeWriteInLegacyStorage(buffer: ByteBuffer): Boolean = {
        contextProvider.get.storage match {
            case Some(legacyStorage) =>
                val encoder = contextProvider.get.encoder
                encoder.decodeFrom(buffer.array)
                val msg = encoder.flowStateMessage
                val conntrackKeys = MutableList.empty[ConnTrackKeyStore]
                val conntrackIter = msg.conntrack()
                while (conntrackIter.hasNext) {
                    val k = connTrackKeyFromSbe(conntrackIter.next(),
                                                ConnTrackKeyStore)
                    conntrackKeys += k
                    log debug s"Got new ConnTrack key: $k"
                }

                val natKeys = MutableList.empty[(NatKeyStore, NatBinding)]
                val natIter = msg.nat()
                while (natIter.hasNext) {
                    val nat = natIter.next()
                    val k = natKeyFromSbe(nat, NatKeyStore)
                    val v = natBindingFromSbe(nat)
                    natKeys += ((k, v))
                    log debug s"Got new NAT mapping: $k -> $v"
                }

                // Bypass trace messages, not interested in them
                val traceIter = msg.trace()
                while (traceIter.hasNext) traceIter.next()
                val reqsIter = msg.traceRequestIds()
                while (reqsIter.hasNext) reqsIter.next()

                val portsIter = msg.portIds()
                if (portsIter.count == 1) {
                    val (ingressPortId, egressPortIds) = portIdsFromSbe(portsIter.next())
                    log debug s"Writing flow state message to legacy storage " +
                              s"for port $ingressPortId."
                    for (k <- conntrackKeys) {
                        legacyStorage.touchConnTrackKey(k, ingressPortId,
                                                        egressPortIds.iterator)
                    }
                    for ((k, v) <- natKeys) {
                        legacyStorage.touchNatKey(k, v, ingressPortId,
                                                  egressPortIds.iterator)
                    }
                    legacyStorage.submit()
                    true
                } else {
                    log.warn(s"Unexpected number (${portsIter.count}) of " +
                             s"ingress/egress port id groups in the flow state " +
                             s"message. Ignoring.")
                    false
                }
            case None =>
                log debug "Not sending flow state to legacy storage, " +
                          "disabled in configuration."
                false
        }
    }

    protected[flowstate] def writeInLocalStorage(buffer: ByteBuffer): Boolean = {
        val encoder = contextProvider.get.encoder
        encoder.decodeFrom(buffer.array)
        val msg = encoder.flowStateMessage
        // Bypass all blocks in the message until portIds
        val conntrackIter = msg.conntrack()
        while (conntrackIter.hasNext) conntrackIter.next()
        val natIter = msg.nat()
        while (natIter.hasNext) natIter.next()
        val traceIter = msg.trace()
        while (traceIter.hasNext) traceIter.next()
        val reqsIter = msg.traceRequestIds()
        while (reqsIter.hasNext) reqsIter.next()

        val portsIter = msg.portIds()
        if (portsIter.count == 1) {
            val (ingressPortId, egressPortIds) = portIdsFromSbe(portsIter.next)
            val matchingPorts = matchPorts(ingressPortId, egressPortIds)
            try {
                val matchingPortsIter = matchingPorts.iterator
                while (matchingPortsIter.hasNext) {
                    val portId = matchingPortsIter.next
                    val writer = getFlowStateWriter(portId)
                    writer.synchronized {
                        log debug s"Writing flow state message to $portId writer."
                        writer.write(encoder)
                    }
                }
                true
            } catch {
                case NonFatal(e) =>
                    log debug s"Failed writing flow state to writer: ${e.getMessage}"
                    false
            }
        } else {
            log.warn(s"Unexpected number (${portsIter.count}) of ingress/egress " +
                     s"port id groups in the flow state message. Ignoring.")
            false
        }
    }

    private def matchPorts(ingressPort: UUID,
                           egressPorts: util.ArrayList[UUID]): util.ArrayList[UUID] = {
        val matchingPorts = contextProvider.get.matchingPorts()
        if (cachedOwnedPortIds.contains(ingressPort)) {
            matchingPorts.add(ingressPort)
        }
        val egressPortIter = egressPorts.iterator()
        while (egressPortIter.hasNext) {
            val egressPort = egressPortIter.next
            if (cachedOwnedPortIds.contains(egressPort)) {
                matchingPorts.add(egressPort)
            }
        }
        matchingPorts
    }
}
