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

package org.midonet.cluster.services.flowstate

import java.util
import java.util.UUID

import scala.util.control.NonFatal

import com.datastax.driver.core.Session
import com.google.common.annotations.VisibleForTesting

import org.midonet.cluster.flowstate.proto.{FlowState => FlowStateSbe}
import org.midonet.cluster.storage.{FlowStateStorage, FlowStateStorageWriter}
import org.midonet.packets.ConnTrackState.ConnTrackKeyStore
import org.midonet.packets.FlowStateStorePackets._
import org.midonet.packets.NatState.NatKeyStore
import org.midonet.packets._

import io.netty.channel.ChannelHandler.Sharable
import io.netty.channel.{ChannelHandlerContext, SimpleChannelInboundHandler}
import io.netty.channel.socket.DatagramPacket

import org.midonet.cluster.services.flowstate.FlowStateService._

object FlowStateMessageHandler {

    val VxlanNHeaderLength = 8

}

trait FlowStateOp
case class PushState(msg: FlowStateSbe) extends FlowStateOp
case class InvalidOp(e: Throwable) extends FlowStateOp

/** Handler used to receive, parse and submit flow state messages from
  * agents to the Cassandra cluster. We reuse this handler for each incomming
  * connection to avoid garbage collection. */
@Sharable
class FlowStateMessageHandler(session: Session)
    extends SimpleChannelInboundHandler[DatagramPacket] {

    import FlowStateMessageHandler._

      /**
      * Flow state storage provider for the calling thread. Necessary as
      * the FlowStateStorage implementation is not thread safe. To overcome
      * this limitation, we use a local thread cache with private copies
      * if the FlowStateStorageImpl object.
      *
      * WARNING: This object assumes that the session parameter is
      * initialized.
      */
    protected val storageProvider: ThreadLocal[FlowStateStorageWriter] =
        new ThreadLocal[FlowStateStorageWriter] {
            override def initialValue(): FlowStateStorageWriter = {
                log debug "Getting the initial value for the flow state storage."
                FlowStateStorage[
                    ConnTrackKeyStore,
                    NatKeyStore](session, NatKeyStore, ConnTrackKeyStore)
            }
        }

    override def channelRead0(ctx: ChannelHandlerContext,
                              msg: DatagramPacket): Unit = {
        log debug s"Datagram packet received: $msg"
        parseDatagram(msg) match {
            case PushState(sbe) => // push state to storage
                pushNewState(sbe)
            case InvalidOp(e) =>
                log warn s"Invalid flow state message, ignoring: $e"
        }
    }

    @VisibleForTesting
    protected[flowstate] def parseDatagram(msg: DatagramPacket): FlowStateOp = {
        try {
            // Ignore the first 8 bytes corresponding to the VXLAN header
            // We don't need to know the actual VNI
            val bb = msg.content().nioBuffer(
                VxlanNHeaderLength, msg.content().capacity()-VxlanNHeaderLength)
            val eth = new Ethernet()
            eth.deserialize(bb)
            val data = FlowStateStorePackets.parseDatagram(eth)
            val encoder = new SbeEncoder()
            val flowStateMessage = encoder.decodeFrom(data.getData)
            log debug s"Flow state message decoded: $flowStateMessage"
            PushState(flowStateMessage)
        } catch {
            case NonFatal(e) =>
                InvalidOp(e)
        }
    }

    @VisibleForTesting
    protected[flowstate] def getStorage = storageProvider.get

    protected[flowstate] def pushNewState(msg: FlowStateSbe): Unit = {
        val storage = getStorage

        // Even if fields in a given block (in this case, the root block) from
        // the SBE message are not used, those need to be read sequentially
        // by the decoding library. You can only skip whole blocks.
        uuidFromSbe(msg.sender)

        val ingressPortId = uuidFromSbe(msg.ingressPortId)

        val egressPortIds: util.ArrayList[UUID] = new util.ArrayList[UUID]
        val egressIter = msg.egressPortIds
        while (egressIter.hasNext) {
            egressPortIds.add(uuidFromSbe(egressIter.next().egressPortId))
        }

        val conntrackIter = msg.conntrack
        while (conntrackIter.hasNext) {
            val k = connTrackKeyFromSbe(conntrackIter.next(), ConnTrackKeyStore)
            log debug s"Got new ConnTrack key: $k"
            storage.touchConnTrackKey(k, ingressPortId, egressPortIds.iterator())
        }

        val natIter = msg.nat
        while (natIter.hasNext) {
            val nat = natIter.next()
            val k = natKeyFromSbe(nat, NatKeyStore)
            val v = natBindingFromSbe(nat)
            log debug s"Got new NAT mapping: $k -> $v"
            storage.touchNatKey(k, v, ingressPortId, egressPortIds.iterator())
        }

        storage.submit()
    }
}
