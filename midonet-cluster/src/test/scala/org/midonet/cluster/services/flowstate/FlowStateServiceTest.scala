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
import java.util.ArrayList
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
import org.mockito.Mockito.{mock, times, verify}
import org.mockito.{Matchers => mockito}
import org.scalatest.junit.JUnitRunner
import org.scalatest.{BeforeAndAfter, FeatureSpec, GivenWhenThen, Matchers}
import org.slf4j.LoggerFactory

import org.midonet.cluster.ClusterNode.Context
import org.midonet.cluster._
import org.midonet.cluster.services.discovery.{MidonetDiscoveryImpl, MidonetServiceURI}
import org.midonet.cluster.storage.{FlowStateStorage, FlowStateStorageWriter}
import org.midonet.cluster.topology.TopologyBuilder
import org.midonet.cluster.util.CuratorTestFramework
import org.midonet.odp.{Packet, FlowMatches}
import org.midonet.packets.ConnTrackState.ConnTrackKeyStore
import org.midonet.packets.NatState.{NatBinding, NatKeyStore}
import org.midonet.packets.TraceState.TraceKeyStore
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

    private var clusterConfig: ClusterConfig = _

    private val executor: ExecutorService = new SameThreadButAfterExecutorService

    /** Mocked flow state minion, overrides local ip discovery */
    private class FlowStateServiceTest(nodeContext: Context, curator: CuratorFramework,
                                       executor: ExecutorService, config: ClusterConfig)
        extends FlowStateService(nodeContext: Context, curator: CuratorFramework,
                                 executor: ExecutorService, config: ClusterConfig) {

        override def initLocalAddress() = address = "4.8.16.32"

        override def startServerFrontEnd() = {
            address shouldBe "4.8.16.32"
            port shouldBe 1234
            super.startServerFrontEnd()
        }

        def getCassandraSession = cassandraSession
    }

    /** Mocked message handler, allows mocking the flow state storage interface */
    private class TestableStorageHandler(session: Session)
        extends FlowStateMessageHandler(session) {

        private var storage: FlowStateStorageWriter = _

        def getStorageProvider = storageProvider

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

    private def randomTraceKey: (UUID, TraceKeyStore) =
        (UUID.randomUUID,
         TraceKeyStore(MAC.random(), MAC.random(), 0, IPv4Addr.random,
                       IPv4Addr.random, 0, Random.nextInt(), Random.nextInt()))


    case class FlowStateProtos(ingressPort: UUID, egressPorts: ArrayList[UUID],
                               conntrackKeys: Seq[ConnTrackKeyStore],
                               natKeys: Seq[(NatKeyStore, NatBinding)])



    private def validFlowStateMessage(numConntracks: Int = 1,
                                      numNats: Int = 1,
                                      numTraces: Int = 0,
                                      numIngressPorts: Int = 1,
                                      numEgressPorts: Int = 1)
    : (DatagramPacket, FlowStateProtos) = {
        var ingressPort: UUID = null
        val egressPorts = new ArrayList[UUID]()
        val conntrackKeys = mutable.MutableList.empty[ConnTrackKeyStore]
        val natKeys = mutable.MutableList.empty[(NatKeyStore, NatBinding)]

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

        clusterConfig = new ClusterConfig(ConfigFactory.parseString(
            s"""
               |zookeeper.zookeeper_hosts = "${zk.getConnectString}"
               |cluster.flow_state.enabled : true
               |cluster.flow_state.vxlan_overlay_udp_port : 1234
               |cassandra.servers : "127.0.0.1:9142"
               |cassandra.cluster : "midonet"
               |cassandra.replication_factor : 1
               |""".stripMargin))

    }

    feature("Test service lifecycle") {
        scenario("Service starts, registers itself, and stops") {
            Given("A discovery service")
            val discovery = new MidonetDiscoveryImpl(
                curator, executor, clusterConfig.backend)
            val client = discovery.getClient[MidonetServiceURI](
                FlowStateStorage.SERVICE_DISCOVERY_NAME)
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
        scenario("A flow state storage object is created per thread.") {
            Given("A flow state service and message handler")
            val context = Context(UUID.randomUUID())
            val service = new FlowStateServiceTest(
                context, curator, executor, clusterConfig)
            service.startAsync().awaitRunning(60, TimeUnit.SECONDS)

            val handler = new TestableStorageHandler(service.getCassandraSession)

            And("Two threads that share the handler")
            class HandlingThread extends Thread {
                var storage: FlowStateStorageWriter = _
                var second_storage: FlowStateStorageWriter = _

                override def run: Unit = {
                    storage = handler.getStorageProvider.get
                    second_storage = handler.getStorageProvider.get
                }
            }
            val executor1 = new HandlingThread()
            val executor2 = new HandlingThread()

            When("Calling twice on storageProvider on two different threads")
            executor1.start()
            executor2.start()

            eventually {
                executor1.storage should not be null
                executor1.second_storage should not be null
                executor2.storage should not be null
                executor2.second_storage should not be null
                Then("Returns the same thread local copy")
                executor1.storage shouldBe executor1.second_storage
                executor2.storage shouldBe executor2.second_storage
                Then("Each thread gets its own copy of the state storage")
                executor1.storage should not be executor2.storage
            }
        }

        scenario("Service handle calls storage with a valid message") {
            Given("A flow state message handler and a valid message")
            val handler = new TestableStorageHandler(null)
            val (datagram, protos) = validFlowStateMessage(
                numIngressPorts = 1, numEgressPorts = 1,
                numConntracks = 1, numNats = 1)

            When("The message is handled")
            handler.channelRead0(null, datagram)

            Then("The received message by the handler is sent to storage")
            val mockedStorage = handler.getStorage
            verify(mockedStorage, times(1)).touchConnTrackKey(
                mockito.eq(protos.conntrackKeys.head),
                mockito.eq(protos.ingressPort), mockito.any())
            verify(mockedStorage, times(1)).touchNatKey(
                mockito.eq(protos.natKeys.head._1),
                mockito.eq(protos.natKeys.head._2),
                mockito.eq(protos.ingressPort), mockito.any())
            verify(mockedStorage, times(1)).submit()
        }

        scenario("Service handle calls storage with trace keys") {
            Given("A flow state handler and a message with trace keys")
            val handler = new TestableStorageHandler(null)
            val (datagram, protos) = validFlowStateMessage(
                numIngressPorts = 1, numEgressPorts = 1,
                numConntracks = 1, numNats = 1, numTraces = 1)

            When("The message is handled")
            handler.channelRead0(null, datagram)

            Then("The received message by the handler is sent to storage")
            val mockedStorage = handler.getStorage
            verify(mockedStorage, times(1)).touchConnTrackKey(
                mockito.eq(protos.conntrackKeys.head),
                mockito.eq(protos.ingressPort), mockito.any())
            verify(mockedStorage, times(1)).touchNatKey(
                mockito.eq(protos.natKeys.head._1),
                mockito.eq(protos.natKeys.head._2),
                mockito.eq(protos.ingressPort), mockito.any())
            verify(mockedStorage, times(1)).submit()
        }

        scenario("Service handle ignores non flow state sbe messages") {
            Given("A flow state message handler and an invalid message")
            val handler = new TestableStorageHandler(null)
            val datagram = invalidFlowStateMessage

            When("the message is handled")
            handler.channelRead0(null, datagram)

            Then("The message is ignored")
            val mockedStorage = handler.getStorage
            verify(mockedStorage, times(0)).touchConnTrackKey(mockito.any(),
                                                              mockito.any(),
                                                              mockito.any())
            verify(mockedStorage, times(0)).touchNatKey(mockito.any(),
                                                        mockito.any(),
                                                        mockito.any(),
                                                        mockito.any())
            verify(mockedStorage, times(0)).submit()
        }

        scenario("Service handle calls storage with valid empty message") {
            Given("A flow state message handler and a message without keys")
            val handler = new TestableStorageHandler(null)
            val (datagram, protos) = validFlowStateMessage(numIngressPorts = 0,
                                                           numEgressPorts = 0,
                                                           numConntracks = 0,
                                                           numNats = 0)

            When("The message is handled")
            handler.channelRead0(null, datagram)

            Then("The handler does not send any key to storage")
            val mockedStorage = handler.getStorage
            verify(mockedStorage, times(0)).touchConnTrackKey(mockito.any(),
                                                              mockito.any(),
                                                              mockito.any())
            verify(mockedStorage, times(0)).touchNatKey(mockito.any(),
                                                        mockito.any(),
                                                        mockito.any(),
                                                        mockito.any())
            verify(mockedStorage, times(0)).submit()

        }

        scenario("Service handle calls to storage with > 1 keys") {
            Given("A flow state message handler and a message with > 1 keys")
            val handler = new TestableStorageHandler(null)
            val (datagram, protos) = validFlowStateMessage(numConntracks = 2,
                                                           numNats = 2)
            When("The message is handled")
            handler.channelRead0(null, datagram)

            Then("The handler does not send any key to storage")
            val mockedStorage = handler.getStorage
            verify(mockedStorage, times(2)).touchConnTrackKey(
                mockito.any(), mockito.eq(protos.ingressPort), mockito.any())
            verify(mockedStorage, times(2)).touchNatKey(
                mockito.any(), mockito.any(), mockito.eq(protos.ingressPort), mockito.any())
            verify(mockedStorage, times(1)).submit()
        }
    }
}
