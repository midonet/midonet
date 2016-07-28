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
import java.util
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

import scala.collection.JavaConversions._
import scala.collection.mutable
import scala.collection.mutable.MutableList
import scala.util.control.NonFatal

import com.datastax.driver.core.Session
import com.google.common.annotations.VisibleForTesting

import org.midonet.cluster.storage.{FlowStateStorage, FlowStateStorageWriter}
import org.midonet.packets.ConnTrackState.ConnTrackKeyStore
import org.midonet.packets.FlowStateStorePackets._
import org.midonet.packets.NatState.{NatBinding, NatKeyStore}
import org.midonet.packets.SbeEncoder
import org.midonet.services.flowstate.FlowStateService._
import org.midonet.services.flowstate.stream.{Context, FlowStateWriter}
import org.midonet.services.flowstate.{FlowStateInternalMessageHeaderSize, FlowStateInternalMessageType}

import io.netty.channel.ChannelHandler.Sharable
import io.netty.channel.socket.DatagramPacket
import io.netty.channel.{ChannelHandlerContext, SimpleChannelInboundHandler}

/** Handler used to receive, parse and submit flow state messages from agents
  * to a local file and, if a legacy flag is active, also to the Cassandra
  * cluster. We reuse this handler for each incoming connection to avoid
  * garbage collection. */
@Sharable
class FlowStateWriteHandler(context: Context,
                            session: Session)
    extends SimpleChannelInboundHandler[DatagramPacket] {

    /**
      * Flow state storage provider for the calling thread. Necessary as
      * the FlowStateStorage implementation is not thread safe. To overcome
      * this limitation, we use a local thread cache with private copies
      * of the FlowStateStorageImpl object.
      *
      * WARNING: This object assumes that the session parameter is
      * initialized if the legacyPushState flag is true.
      */
    protected val storageProvider: ThreadLocal[FlowStateStorageWriter] = {
        if (context.config.legacyPushState) {
            new ThreadLocal[FlowStateStorageWriter] {
                override def initialValue(): FlowStateStorageWriter = {
                    Log debug "Getting the initial value for the flow state storage."
                    FlowStateStorage[ConnTrackKeyStore, NatKeyStore](
                        session, NatKeyStore, ConnTrackKeyStore)
                }
            }
        } else {
            null
        }
    }

    protected[flowstate] val portWriters = new ConcurrentHashMap[UUID, FlowStateWriter]()

    @volatile
    protected[flowstate] var cachedOwnedPortIds: Set[UUID] = Set.empty[UUID]

    // Metrics
    val duration = context.registry.timer("writeRate")

    override def channelRead0(ctx: ChannelHandlerContext,
                              msg: DatagramPacket): Unit = {
        val elapsedTime = duration.time()
        try {
            val bb = msg.content().nioBuffer(0, FlowStateInternalMessageHeaderSize)
            val messageType = bb.getInt
            val messageSize = bb.getInt
            val messageData = msg.content().nioBuffer(FlowStateInternalMessageHeaderSize, messageSize)
            messageType match {
                case FlowStateInternalMessageType.FlowStateMessage =>
                    handleFlowStateMessage(messageData)
                case FlowStateInternalMessageType.OwnedPortsUpdate =>
                    handleUpdateOwnedPorts(messageData)
                case _ =>
                    Log warn s"Invalid flow state message header, ignoring."
            }
        } catch {
            case NonFatal(e) =>
                Log.error(s"Unkown error handling internal flow state message", e)
        }
        elapsedTime.stop()
    }

    private def handleFlowStateMessage(buffer: ByteBuffer): Unit = {
        val encoder = new SbeEncoder()
        val data = new Array[Byte](buffer.capacity())
        buffer.get(data)
        val flowStateMessage = encoder.decodeFrom(data)
        pushNewState(encoder)
    }

    private def handleUpdateOwnedPorts(buffer: ByteBuffer): Unit = {
        var ownedPorts = Set.empty[UUID]
        while (buffer.position < buffer.limit()) {
            ownedPorts += new UUID(buffer.getLong, buffer.getLong)
        }
        Log debug s"Received new owned ports: $ownedPorts}"
        // check the difference to release the writers
        val unboundPorts = cachedOwnedPortIds -- ownedPorts
        for (unboundPort <- unboundPorts) {
            context.ioManager.close(unboundPort)
        }
        cachedOwnedPortIds = ownedPorts
    }

    @VisibleForTesting
    protected[flowstate] def getLegacyStorage = storageProvider.get

    protected[flowstate] def getFlowStateWriter(portId: UUID) =
        context.ioManager.stateWriter(portId)

    protected[flowstate] def pushNewState(encoder: SbeEncoder): Unit = {
        val msg = encoder.flowStateMessage
        uuidFromSbe(msg.sender)

        val conntrackKeys = MutableList.empty[ConnTrackKeyStore]
        val conntrackIter = msg.conntrack
        while (conntrackIter.hasNext) {
            val k = connTrackKeyFromSbe(conntrackIter.next(), ConnTrackKeyStore)
            conntrackKeys += k
            Log debug s"Got new ConnTrack key: $k"
        }

        val natKeys = MutableList.empty[(NatKeyStore, NatBinding)]
        val natIter = msg.nat
        while (natIter.hasNext) {
            val nat = natIter.next()
            val k = natKeyFromSbe(nat, NatKeyStore)
            val v = natBindingFromSbe(nat)
            natKeys += ((k, v))
            Log debug s"Got new NAT mapping: $k -> $v"
        }

        // Bypass trace messages, not interested in them
        val traceIter = msg.trace
        while (traceIter.hasNext) traceIter.next
        val reqsIter = msg.traceRequestIds
        while (reqsIter.hasNext) reqsIter.next

        // There's only one group element of portIds in the message
        val portsIter = msg.portIds
        if (portsIter.count == 1) {
            val (ingressPortId, egressPortIds) = portIdsFromSbe(portsIter.next)
            if (context.config.legacyPushState) {
                writeInLegacyStorage(
                    ingressPortId, egressPortIds, conntrackKeys, natKeys)
            }
            if (context.config.localPushState) {
                writeInLocalStorage(ingressPortId, egressPortIds, encoder)
            }
        } else {
            Log.warn(s"Unexpected number (${portsIter.count}) of ingress/egress " +
                     s"port id groups in the flow state message. Ignoring.")
        }
    }

    protected[flowstate] def writeInLegacyStorage(ingressPortId: UUID,
        egressPortIds: util.ArrayList[UUID],
        conntrackKeys: mutable.MutableList[ConnTrackKeyStore],
        natKeys: mutable.MutableList[(NatKeyStore, NatBinding)]) = {

        Log debug s"Writing flow state message to legacy storage for port $ingressPortId."

        val legacyStorage = getLegacyStorage

        for (k <- conntrackKeys) {
            legacyStorage.touchConnTrackKey(k, ingressPortId, egressPortIds.iterator)
        }

        for ((k, v) <- natKeys) {
            legacyStorage.touchNatKey(k, v, ingressPortId, egressPortIds.iterator)
        }

        legacyStorage.submit()
    }

    protected[flowstate] def writeInLocalStorage(ingressPortId: UUID,
                                                 egressPortIds: util.ArrayList[UUID],
                                                 encoder: SbeEncoder): Unit = {
        val matchingPorts = mutable.Set.empty[UUID]
        if (cachedOwnedPortIds.contains(ingressPortId)) {
            matchingPorts += ingressPortId
        }
        for (egressPortId <- egressPortIds) {
            if (cachedOwnedPortIds.contains(egressPortId)) {
                matchingPorts += egressPortId
            }
        }

        for (portId <- matchingPorts) {
            val writer = getFlowStateWriter(portId)
            writer.synchronized {
                Log debug s"Writing flow state message to $portId writer."
                writer.write(encoder)
            }
        }
    }
}
