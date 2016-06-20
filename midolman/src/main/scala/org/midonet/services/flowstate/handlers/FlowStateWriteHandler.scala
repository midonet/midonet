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

import java.util.concurrent.ConcurrentHashMap
import java.util.{UUID, List => JList}

import scala.collection.mutable.MutableList
import scala.util.control.NonFatal

import com.datastax.driver.core.Session
import com.google.common.annotations.VisibleForTesting

import org.midonet.cluster.flowstate.proto.{FlowState => FlowStateSbe}
import org.midonet.cluster.storage.{FlowStateStorage, FlowStateStorageWriter}
import org.midonet.midolman.config.FlowStateConfig
import org.midonet.packets.ConnTrackState.ConnTrackKeyStore
import org.midonet.packets.FlowStateStorePackets._
import org.midonet.packets.NatState.{NatBinding, NatKeyStore}
import org.midonet.packets.SbeEncoder
import org.midonet.services.flowstate.FlowStateService._
import org.midonet.services.flowstate.stream._

import io.netty.channel.ChannelHandler.Sharable
import io.netty.channel.socket.DatagramPacket
import io.netty.channel.{ChannelHandlerContext, SimpleChannelInboundHandler}

trait FlowStateOp
case class PushState(msg: FlowStateSbe, encoder: SbeEncoder) extends FlowStateOp
case class InvalidOp(e: Throwable) extends FlowStateOp

/** Handler used to receive, parse and submit flow state messages from agents
  * to a local file and, if a legacy flag is active, also to the Cassandra
  * cluster. We reuse this handler for each incoming connection to avoid
  * garbage collection. */
@Sharable
class FlowStateWriteHandler(config: FlowStateConfig,
                            session: Session)
    extends SimpleChannelInboundHandler[DatagramPacket] {

    protected val portWriters = new ConcurrentHashMap[UUID, FlowStateWriter]()

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
        if (config.legacyPushState) {
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

    override def channelRead0(ctx: ChannelHandlerContext,
                              msg: DatagramPacket): Unit = {
        Log debug s"Datagram packet received: $msg"
        parseDatagram(msg) match {
            case PushState(sbe, encoder) =>
                 pushNewState(sbe, encoder)
            case InvalidOp(e) =>
                Log warn s"Invalid flow state message, ignoring: $e"
        }
    }

    @VisibleForTesting
    protected[flowstate] def parseDatagram(msg: DatagramPacket): FlowStateOp = {
        try {
            val bb = msg.content().nioBuffer(0, msg.content().capacity())
            val encoder = new SbeEncoder()
            val data = new Array[Byte](bb.remaining())
            bb.get(data)
            val flowStateMessage = encoder.decodeFrom(data)
            Log debug s"Flow state message decoded: $flowStateMessage"
            PushState(flowStateMessage, encoder)
        } catch {
            case NonFatal(e) =>
                InvalidOp(e)
        }
    }

    @VisibleForTesting
    protected[flowstate] def getLegacyStorage = storageProvider.get

    /*
     * TODO: When we finally remove Cassandra we can bypass conntrackKeys and
     * natKeys, just like we are doing with traces
     */
    private def pushNewState(msg: FlowStateSbe, encoder: SbeEncoder): Unit = {
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

            if (config.localPushState) {
                writeInLocalStorage(ingressPortId, encoder)
            }

            if (config.legacyPushState) {
                legacyPushNewState(ingressPortId, egressPortIds,
                    conntrackKeys, natKeys)
            }
        } else {
            Log.warn(s"Unexpected number (${portsIter.count}) of ingress/egress " +
                     s"port id groups in the flow state message.")
        }
    }

    @VisibleForTesting
    protected def writeInLocalStorage(portId: UUID, encoder: SbeEncoder): Unit =
        getFlowStateWriter(portId).write(encoder)

    private def getFlowStateWriter(portId: UUID) = portWriters.synchronized {
        if (portWriters.containsKey(portId)) {
            portWriters.get(portId)
        } else {
            val writer = FlowStateWriter(config, portId)
            portWriters.put(portId, writer)
            writer
        }
    }

    private def legacyPushNewState(ingressPortId: UUID,
                                   egressPortIds: JList[UUID],
                                   conntrackKeys: MutableList[ConnTrackKeyStore],
                                   natKeys: MutableList[(NatKeyStore, NatBinding)]) {
        val legacyStorage = getLegacyStorage

        for (k <- conntrackKeys) {
            legacyStorage.touchConnTrackKey(k, ingressPortId, egressPortIds.iterator)
        }

        for ((k, v) <- natKeys) {
            legacyStorage.touchNatKey(k, v, ingressPortId, egressPortIds.iterator)
        }

        legacyStorage.submit()
    }
}
