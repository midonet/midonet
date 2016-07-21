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

import java.net.{InetSocketAddress, ServerSocket}
import java.nio.ByteBuffer
import java.util
import java.util.UUID
import java.util.UUID.randomUUID

import scala.collection.mutable
import scala.util.Random
import scala.util.control.NonFatal

import com.google.common.io.Files

import org.scalatest.{BeforeAndAfter, FeatureSpec, GivenWhenThen, Matchers}

import org.midonet.cluster.flowstate.FlowStateTransfer.StateRequest
import org.midonet.midolman.config.MidolmanConfig
import org.midonet.midolman.logging.MidolmanLogging
import org.midonet.packets.ConnTrackState.ConnTrackKeyStore
import org.midonet.packets.FlowStateStorePackets._
import org.midonet.packets.NatState.{NatBinding, NatKeyStore}
import org.midonet.packets.TraceState.TraceKeyStore
import org.midonet.packets._
import org.midonet.services.flowstate.stream.FlowStateWriter
import org.midonet.services.flowstate.transfer.StateTransferProtocolBuilder._
import org.midonet.util.MidonetEventually

import io.netty.buffer.Unpooled
import io.netty.channel.socket.DatagramPacket

trait FlowStateBaseTest extends FeatureSpec
                                with GivenWhenThen with Matchers
                                with BeforeAndAfter with MidonetEventually
                                with MidolmanLogging {

    // It returns a new configuration with different ports and
    // temporal directory on each invocation
    protected def midolmanConfig: MidolmanConfig =
        MidolmanConfig.forTests(getConfig)

    protected def getConfig = {
        s"""
           |agent.minions.flow_state.enabled : true
           |agent.minions.flow_state.legacy_push_state : true
           |agent.minions.flow_state.legacy_read_state : true
           |agent.minions.flow_state.local_push_state : true
           |agent.minions.flow_state.local_read_state : true
           |agent.minions.flow_state.port : $getFreePort
           |agent.minions.flow_state.connection_timeout : 5s
           |agent.minions.flow_state.block_size : 1
           |agent.minions.flow_state.blocks_per_port : 10
           |agent.minions.flow_state.expiration_delay : 5s
           |agent.minions.flow_state.clean_unused_files_delay : 5s
           |agent.minions.flow_state.log_directory: ${Files.createTempDir().getName}
           |cassandra.servers : "127.0.0.1:9142"
           |cassandra.cluster : "midonet"
           |cassandra.replication_factor : 1
           |""".stripMargin
    }

    protected def getFreePort: Int = {
        // Binding a socket to port 0 makes the OS returns us a free ephemeral
        // port. Return this port so jetty binds to it.
        var localPort: Int = -1
        while (localPort == -1) {
            var ss: ServerSocket = null
            try {
                ss = new ServerSocket(0)
                ss.setReuseAddress(true)
                localPort = ss.getLocalPort
            } catch {
                case NonFatal(e) =>
            } finally {
                if (ss != null) {
                    try {
                        ss.close()
                    } catch {
                        case NonFatal(e) =>
                    }
                }
            }
        }
        log.debug(s"Using port $localPort")
        localPort
    }

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


    protected def validOwnedPortsMessage(portIds: Set[UUID], port: Short = 6688)
    : DatagramPacket = {
        val udpBuffer = ByteBuffer.allocate(MaxMessageSize)
        udpBuffer.putInt(FlowStateInternalMessageType.OwnedPortsUpdate)
        udpBuffer.putInt(portIds.size * 16) // UUID size = 16 bytes
        for (portId <- portIds) {
            udpBuffer.putLong(portId.getMostSignificantBits)
            udpBuffer.putLong(portId.getLeastSignificantBits)
        }
        udpBuffer.flip()

        new DatagramPacket(
            Unpooled.wrappedBuffer(
                udpBuffer.array, 0,
                FlowStateInternalMessageHeaderSize + portIds.size * 16),
            new InetSocketAddress(port))
    }

    protected def validFlowStateInternalMessage(numConntracks: Int = 1,
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
        val udpBuffer = ByteBuffer.allocate(
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

        udpBuffer.putInt(FlowStateInternalMessageType.FlowStateMessage)
        udpBuffer.putInt(encoder.encodedLength())
        udpBuffer.put(encoder.flowStateBuffer.array, 0, encoder.encodedLength())
        udpBuffer.flip()

        val udp = new DatagramPacket(
            Unpooled.wrappedBuffer(udpBuffer),
            new InetSocketAddress(port))

        val protos = FlowStateProtos(ingressPort, egressPorts, conntrackKeys, natKeys)

        (udp, protos, encoder)
    }

    protected def createValidFlowStatePorts(context: stream.Context) = {
        val validPorts = (1 to 3) map { _ => randomUUID }

        validPorts foreach { port =>
            val flowstate = validFlowStateInternalMessage(numNats = 2,
                numEgressPorts = 3)._3

            val writer: FlowStateWriter = FlowStateWriter(context, port)
            writer.write(flowstate)
            writer.flush()
        }

        validPorts
    }

    protected def invalidFlowStateMessage(port: Int = 6688): DatagramPacket = {
        val buffer = new Array[Byte](
            FlowStateEthernet.FLOW_STATE_MAX_PAYLOAD_LENGTH)

        Random.nextBytes(buffer)
        new DatagramPacket(Unpooled.wrappedBuffer(buffer),
                           new InetSocketAddress(port))
    }

    protected def rawStateRequest(port: UUID = randomUUID) = {
        val request = buildStateRequestRaw(port)
        Unpooled.wrappedBuffer(request.toByteArray)
    }

    protected def internalStateRequest(port: UUID = randomUUID) = {
        val request = buildStateRequestInternal(port)
        Unpooled.wrappedBuffer(request.toByteArray)
    }

    protected def remoteStateRequest(port: UUID = randomUUID) = {
        val request = buildStateRequestRemote(port, "127.0.0.1")
        Unpooled.wrappedBuffer(request.toByteArray)
    }

    protected def invalidStateTransferRequest = {
        val invalidRequest = StateRequest.newBuilder().build()
        Unpooled.wrappedBuffer(invalidRequest.toByteArray)
    }

    protected def malformedStateTransferRequest = {
        val buffer = new Array[Byte](100)
        Random.nextBytes(buffer)
        Unpooled.wrappedBuffer(buffer)
    }

}
