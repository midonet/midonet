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

import java.net.InetSocketAddress
import java.util.UUID
import java.util.concurrent.{ExecutorService, TimeUnit}

import scala.collection.mutable
import scala.util.Random

import com.datastax.driver.core.Session
import com.typesafe.config.ConfigFactory
import com.typesafe.scalalogging.Logger

import org.apache.curator.framework.CuratorFramework
import org.cassandraunit.utils.EmbeddedCassandraServerHelper
import org.junit.runner.RunWith
import org.mockito.Mockito._
import org.mockito.Matchers._
import org.scalatest.junit.JUnitRunner
import org.scalatest.{BeforeAndAfter, FeatureSpec, GivenWhenThen, Matchers}
import org.slf4j.LoggerFactory

import org.midonet.cluster.ClusterNode.Context
import org.midonet.cluster._
import org.midonet.cluster.services.discovery.{MidonetDiscoveryImpl, MidonetServiceURI}
import org.midonet.cluster.storage.FlowStateStorageWriter
import org.midonet.cluster.topology.TopologyBuilder
import org.midonet.cluster.util.CuratorTestFramework
import org.midonet.odp.{Packet, FlowMatches}
import org.midonet.packets.ConnTrackState.ConnTrackKeyStore
import org.midonet.packets.NatState.{NatBinding, NatKeyStore}
import org.midonet.packets._
import org.midonet.util.MidonetEventually
import org.midonet.util.concurrent.SameThreadButAfterExecutorService


import io.netty.buffer.Unpooled
import io.netty.channel.socket.DatagramPacket

import FlowStateStorePackets._

@RunWith(classOf[JUnitRunner])
class FlowStateServiceTest extends FeatureSpec with GivenWhenThen with Matchers
                                   with BeforeAndAfter with MidonetEventually
                                   with TopologyBuilder with CuratorTestFramework {

    private val log = Logger(LoggerFactory.getLogger(flowStateLog))

    private val clusterConfig = new ClusterConfig(ConfigFactory.parseString(
        """
          |zookeeper.zookeeper_hosts = "127.0.0.1:2181"
          |cluster.flow_state.enabled : true
          |cluster.flow_state.vxlan_overlay_udp_port : 1234
          |cassandra.servers : "127.0.0.1:9142"
          |cassandra.cluster : "midonet"
          |cassandra.replication_factor : 1
        """.stripMargin))

    private val executor: ExecutorService = new SameThreadButAfterExecutorService

    /** Mocked flow state minion, overrides local ip discovery */
    private class FlowStateServiceTest(nodeContext: Context, curator: CuratorFramework,
                                       executor: ExecutorService, config: ClusterConfig)
        extends FlowStateService(nodeContext: Context, curator: CuratorFramework,
                                 executor: ExecutorService, config: ClusterConfig) {

        override def localAddress = "4.8.16.32"

        override def startServerFrontEnd() = {
            address shouldBe localAddress
            port shouldBe 1234
            super.startServerFrontEnd()
        }
    }

    /** Mocked message handler, allows mocking the flow state storage interface */
    private class TestableStorageHandler(log: Logger, session: Session)
        extends FlowStateMessageHandler(log, session) {

        private var storage: FlowStateStorageWriter = _

        override def getStorage = {
            if (storage eq null)
                storage = mock(classOf[FlowStateStorageWriter])
            storage
        }
    }

    private def randomPort: Int = Random.nextInt(Short.MaxValue + 1)

    private def randomConnTrackKey: ConnTrackKeyStore =
        ConnTrackKeyStore(IPv4Addr.random, randomPort,
                          IPv4Addr.random, randomPort,
                          0, UUID.randomUUID)

    private def randomNatKey: NatKeyStore =
        NatKeyStore(NatState.FWD_DNAT,
                    IPv4Addr.random, randomPort,
                    IPv4Addr.random, randomPort,
                    1, UUID.randomUUID)

    private def randomNatBinding: NatBinding =
        NatBinding(IPv4Addr.random, randomPort)

    case class FlowStateProtos(ingressPort: UUID, egressPorts: Seq[UUID],
                               conntrackKeys: Seq[ConnTrackKeyStore],
                               natKeys: Seq[NatKeyStore])

    private def validFlowStateMessage(withIngressPort: Boolean = true,
                                      numEgressPorts: Int = 1,
                                      numConntracks: Int = 1,
                                      numNats: Int = 1)
    : (DatagramPacket, FlowStateProtos) = {
        var ingressPort: UUID = null
        val egressPorts = mutable.MutableList.empty[UUID]
        val conntrackKeys = mutable.MutableList.empty[ConnTrackKeyStore]
        val natKeys = mutable.MutableList.empty[NatKeyStore]

        // Prepare UDP shell
        val buffer = new Array[Byte](
            FlowStateEthernet.FLOW_STATE_MAX_PAYLOAD_LENGTH)
        val packet = {
            val udpShell = new FlowStateEthernet(buffer)
            new Packet(udpShell, FlowMatches.fromEthernetPacket(udpShell))
        }

        // Encode flow state message into buffer
        val encoder = new SbeEncoder()
        val flowStateMessage = encoder.encodeTo(buffer)

        // Encode sender
        val sender = UUID.randomUUID()
        uuidToSbe(sender, flowStateMessage.sender)

        // Encode ingress/egress ports
        if (withIngressPort) {
            ingressPort = UUID.randomUUID
            uuidToSbe(ingressPort, flowStateMessage.ingressPortId)
        }

        val e = flowStateMessage.egressPortIdsCount(numEgressPorts)
        while (e.hasNext) {
            val egressPort = UUID.randomUUID()
            egressPorts += egressPort
            egressPortIdToSbe(egressPort, e.next)
        }

        // Encode keys
        val c = flowStateMessage.conntrackCount(numConntracks)
        while (c.hasNext) {
            val conntrackKey = randomConnTrackKey
            conntrackKeys += conntrackKey
            connTrackKeyToSbe(conntrackKey, c.next)
        }

        val n = flowStateMessage.natCount(numNats)
        while (n.hasNext) {
            val natKey = randomNatKey
            natKeys += natKey
            natToSbe(natKey, randomNatBinding, n.next)
        }


        // Set the limit and prepare the netty datagram object
        val len = encoder.encodedLength()
        val fse = packet.getEthernet.asInstanceOf[FlowStateEthernet]
        fse.limit(len)
        val datagram = new VXLAN().setPayload(fse).serialize()
        val udp = new DatagramPacket(Unpooled.wrappedBuffer(datagram),
                                     new InetSocketAddress(randomPort))
        val protos = FlowStateProtos(ingressPort, egressPorts, conntrackKeys, natKeys)

        (udp, protos)
    }

    private def invalidFlowStateMessage: DatagramPacket = {
        // Prepare UDP shell
        val buffer = new Array[Byte](
            FlowStateEthernet.FLOW_STATE_MAX_PAYLOAD_LENGTH)
        val packet = {
            val udpShell = new FlowStateEthernet(buffer)
            new Packet(udpShell, FlowMatches.fromEthernetPacket(udpShell))
        }

        val encoder = new SbeEncoder()
        val flowStateMessage = encoder.encodeTo(buffer)
        Random.nextBytes(buffer)
        val fse = packet.getEthernet.asInstanceOf[FlowStateEthernet]
        fse.limit(FlowStateEthernet.FLOW_STATE_MAX_PAYLOAD_LENGTH)
        val datagram = new VXLAN().setPayload(fse).serialize()
        new DatagramPacket(Unpooled.wrappedBuffer(datagram),
                           new InetSocketAddress(randomPort))
    }

    before {
        EmbeddedCassandraServerHelper.startEmbeddedCassandra(60000L)
        Thread.sleep(15000L)
    }

    feature("Test service lifecycle") {
        scenario("Service starts, registers itself, and stops") {
            Given("A discovery service")
            val discovery = new MidonetDiscoveryImpl(
                curator, executor, clusterConfig.backend)
            val client = discovery.getClient[MidonetServiceURI]("flowstate")
            And("A container service that is started")
            val context = Context(UUID.randomUUID())
            val service = new FlowStateServiceTest(
                context, curator, executor, clusterConfig)
            service.startAsync().awaitRunning(60, TimeUnit.SECONDS)

            Then("The instance is registered in the discovery service")
            eventually {
                val instances = client.instances
                instances should have size 1
                instances.head.uri.getHost shouldBe "4.8.16.32"
                instances.head.uri.getPort shouldBe 1234
            }

            When("The service is stopped")
            service.stopAsync().awaitTerminated(10, TimeUnit.SECONDS)

            Then("The service should be removed from the discovery service")
            eventually {
                val instances = client.instances
                instances should have size 0
            }
        }

        scenario("Service is enabled in the default configuration schema") {
            Given("A flow state service that is started")
            val service = new FlowStateServiceTest(
                Context(UUID.randomUUID()), curator, executor, clusterConfig)

            Then("The service is enabled")
            service.isEnabled shouldBe true
        }
    }


    feature("Message handling") {
        scenario("Service handle calls storage with a valid message") {
            Given("A flow state message handler and a valid message")
            val handler = new TestableStorageHandler(log, null)
            val (datagram, protos) = validFlowStateMessage(
                withIngressPort = true, numEgressPorts = 1,
                numConntracks = 1, numNats = 1)

            When("The message is handled")
            handler.channelRead0(null, datagram)

            Then("The received message by the handler is sent to storage")
            val mockedStorage = handler.getStorage
            verify(mockedStorage, times(1)).touchConnTrackKey(any(), any(), any())
            verify(mockedStorage, times(1)).touchNatKey(any(), any(), any(), any())
            verify(mockedStorage, times(1)).submit()
        }

        scenario("Service handle ignores non flow state sbe messages") {
            Given("A flow state message handler and an invalid message")
            val handler = new TestableStorageHandler(log, null)
            val datagram = invalidFlowStateMessage

            When("the message is handled")
            handler.channelRead0(null, datagram)

            Then("The message is ignored")
            val mockedStorage = handler.getStorage
            verify(mockedStorage, times(0)).touchConnTrackKey(any(), any(), any())
            verify(mockedStorage, times(0)).touchNatKey(any(), any(), any(), any())
            verify(mockedStorage, times(0)).submit()
        }

        scenario("Service handle calls storage with valid empty message") {
            Given("A flow state message handler and a message without keys")
            val handler = new TestableStorageHandler(log, null)
            val (datagram, protos) = validFlowStateMessage(withIngressPort = false,
                                                           numEgressPorts = 0,
                                                           numConntracks = 0,
                                                           numNats = 0)

            When("The message is handled")
            handler.channelRead0(null, datagram)

            Then("The handler does not send any key to storage")
            val mockedStorage = handler.getStorage
            verify(mockedStorage, times(0)).touchConnTrackKey(any(), any(), any())
            verify(mockedStorage, times(0)).touchNatKey(any(), any(), any(), any())
            verify(mockedStorage, times(1)).submit()

        }

        scenario("Service handle calls to storage with > 1 keys") {
            Given("A flow state message handler and a message with > 1 keys")
            val handler = new TestableStorageHandler(log, null)
            val (datagram, protos) = validFlowStateMessage(numConntracks = 2,
                                                           numNats = 2)
            When("The message is handled")
            handler.channelRead0(null, datagram)

            Then("The handler does not send any key to storage")
            val mockedStorage = handler.getStorage
            verify(mockedStorage, times(2)).touchConnTrackKey(any(), any(), any())
            verify(mockedStorage, times(2)).touchNatKey(any(), any(), any(), any())
            verify(mockedStorage, times(1)).submit()
        }
    }
}
