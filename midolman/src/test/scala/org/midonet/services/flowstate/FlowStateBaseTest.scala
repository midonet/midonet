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

package org.midonet.services.flowstate

import java.net.InetSocketAddress
import java.util
import java.util.UUID

import scala.collection.mutable
import scala.util.Random

import org.scalatest.{BeforeAndAfter, GivenWhenThen, Matchers, FeatureSpec}

import org.midonet.midolman.logging.MidolmanLogging
import org.midonet.packets.ConnTrackState.ConnTrackKeyStore
import org.midonet.packets.FlowStateStorePackets._
import org.midonet.packets.NatState.{NatBinding, NatKeyStore}
import org.midonet.packets.TraceState.TraceKeyStore
import org.midonet.packets._
import org.midonet.util.MidonetEventually

import io.netty.buffer.Unpooled
import io.netty.channel.socket.DatagramPacket

class FlowStateBaseTest extends FeatureSpec
                                with GivenWhenThen with Matchers
                                with BeforeAndAfter with MidonetEventually with MidolmanLogging {


    protected def randomPort: Int = Random.nextInt(Short.MaxValue + 1)

    protected def randomConnTrackKey: ConnTrackKeyStore =
        ConnTrackKeyStore(IPv4Addr.random, randomPort,
                          IPv4Addr.random, randomPort,
                          0, UUID.randomUUID)

    protected def randomNatKey: NatKeyStore =
        NatKeyStore(NatState.FWD_DNAT,
                    IPv4Addr.random, randomPort,
                    IPv4Addr.random, randomPort,
                    1, UUID.randomUUID)

    protected def randomNatBinding: NatBinding =
        NatBinding(IPv4Addr.random, randomPort)

    protected def randomTraceKey: (UUID, TraceKeyStore) =
        (UUID.randomUUID,
            TraceKeyStore(MAC.random(), MAC.random(), 0, IPv4Addr.random,
                          IPv4Addr.random, 0, Random.nextInt(), Random.nextInt()))


    case class FlowStateProtos(ingressPort: UUID, egressPorts: util.ArrayList[UUID],
                               conntrackKeys: Seq[ConnTrackKeyStore],
                               natKeys: Seq[(NatKeyStore, NatBinding)])



    protected def validFlowStateMessage(numConntracks: Int = 1,
                                        numNats: Int = 1,
                                        numTraces: Int = 0,
                                        numIngressPorts: Int = 1,
                                        numEgressPorts: Int = 1,
                                        port: Short = 6688)
    : (DatagramPacket, FlowStateProtos, SbeEncoder) = {
        var ingressPort: UUID = null
        val egressPorts = new util.ArrayList[UUID]()
        val conntrackKeys = mutable.MutableList.empty[ConnTrackKeyStore]
        val natKeys = mutable.MutableList.empty[(NatKeyStore, NatBinding)]

        // Prepare UDP shell
        val buffer = new Array[Byte](
            FlowStateEthernet.FLOW_STATE_MAX_PAYLOAD_LENGTH)

        // Encode flow state message into buffer
        val encoder = new SbeEncoder()
        val flowStateMessage = encoder.encodeTo(buffer)

        // Encode sender
        val sender = UUID.randomUUID()
        uuidToSbe(sender, flowStateMessage.sender)

        // Encode keys
        val c = flowStateMessage.conntrackCount(numConntracks)
        while (c.hasNext) {
            val conntrackKey = randomConnTrackKey
            conntrackKeys += conntrackKey
            connTrackKeyToSbe(conntrackKey, c.next)
        }

        val n = flowStateMessage.natCount(numNats)
        while (n.hasNext) {
            val (natKey, natBinding) = (randomNatKey, randomNatBinding)
            natKeys += ((natKey, natBinding))
            natToSbe(natKey, natBinding, n.next)
        }

        val t = flowStateMessage.traceCount(numTraces)
        while (t.hasNext) {
            val (traceId, traceKey) = randomTraceKey
            traceToSbe(traceId, traceKey, t.next)
        }

        val r = flowStateMessage.traceRequestIdsCount(numTraces)
        while (r.hasNext) {
            uuidToSbe(UUID.randomUUID, r.next().id)
        }

        // Encode ingress/egress ports
        if (numIngressPorts > 0 && numEgressPorts > 0) {
            val p = flowStateMessage.portIdsCount(1)
            ingressPort = UUID.randomUUID()

            for (i <- 1 to numEgressPorts) {
                egressPorts.add(UUID.randomUUID)
            }

            portIdsToSbe(ingressPort, egressPorts, p.next)
        } else {
            flowStateMessage.portIdsCount(0)
        }

        val udp = new DatagramPacket(
            Unpooled.wrappedBuffer(buffer),
            new InetSocketAddress(port))

        val protos = FlowStateProtos(ingressPort, egressPorts, conntrackKeys, natKeys)

        (udp, protos, encoder)
    }

    protected def invalidFlowStateMessage(port: Int = 6688): DatagramPacket = {
        val buffer = new Array[Byte](
            FlowStateEthernet.FLOW_STATE_MAX_PAYLOAD_LENGTH)

        Random.nextBytes(buffer)
        new DatagramPacket(Unpooled.wrappedBuffer(buffer),
                           new InetSocketAddress(port))
    }

}
