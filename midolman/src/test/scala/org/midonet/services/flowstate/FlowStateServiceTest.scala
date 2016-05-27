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


import java.net.{BindException, DatagramSocket, ServerSocket}
import java.util.concurrent.{ExecutorService, TimeUnit}
import java.util.UUID

import com.typesafe.config.ConfigFactory
import io.netty.buffer.ByteBuf
import io.netty.channel.ChannelHandlerContext
import org.apache.curator.framework.CuratorFramework
import org.cassandraunit.utils.EmbeddedCassandraServerHelper
import org.junit.runner.RunWith
import org.midonet.cluster.flowstate.FlowStateTransfer.StateForPortResponse
import org.mockito.Mockito.{mock, spy, times, verify}
import org.mockito.{ArgumentCaptor, Matchers => mockito}
import org.scalatest.junit.JUnitRunner
import org.midonet.cluster.storage.FlowStateStorageWriter
import org.midonet.cluster.topology.TopologyBuilder
import org.midonet.cluster.util.CuratorTestFramework
import org.midonet.midolman.config.{FlowStateConfig, MidolmanConfig}
import org.midonet.minion.Context
import org.midonet.services.flowstate.handlers.{FlowStateInternalMessageHandler, FlowStateRemoteMessageHandler}
import org.midonet.services.flowstate.transfer.StateTransferProtocolParser.parseTransferResponse
import org.midonet.services.flowstate.transfer.internal.{ErrorCode, StateTransferAck, StateTransferError}
import org.midonet.util.concurrent.SameThreadButAfterExecutorService

@RunWith(classOf[JUnitRunner])
class FlowStateServiceTest extends FlowStateBaseTest
                                   with TopologyBuilder with CuratorTestFramework {

    private var midolmanConfig: MidolmanConfig = _

    private val executor: ExecutorService = new SameThreadButAfterExecutorService

    /** Mocked flow state minion, overrides local ip discovery */
    private class FlowStateServiceTest(nodeContext: Context, curator: CuratorFramework,
                                       executor: ExecutorService, config: MidolmanConfig)
        extends FlowStateService(nodeContext: Context, curator: CuratorFramework,
                                 executor: ExecutorService, config: MidolmanConfig) {

        override def startServerFrontEnds() = {
            udpPort shouldBe 1234
            tcpPort shouldBe 4321
            super.startServerFrontEnds()
        }

        def getUdpMessageHandler = internalMessageHandler
        def getTcpMessageHandler = remoteMessageHandler
    }

    /** Mocked internal handler, allows mocking the flow state storage interface */
    private class TestableInternalHandler(config: FlowStateConfig)
        extends FlowStateInternalMessageHandler(null, null, config) {

        private var legacyStorage: FlowStateStorageWriter = _
        private var localStorageProvider: FlowStateStorageProvider = _

        def getStorageProvider = storageProvider

        override def getLegacyStorage = {
            if (legacyStorage eq null)
                legacyStorage = mock(classOf[FlowStateStorageWriter])
            legacyStorage
        }

        override def getLocalStorageProvider = {
            if (localStorageProvider eq null)
                localStorageProvider = mock(classOf[FlowStateStorageProvider])
            localStorageProvider
        }
    }

    /** Mocked remote handler, allows mocking the flow state transfer interface */
    private class TestableRemoteHandler
        extends FlowStateRemoteMessageHandler(null) {

        private var localStorageProvider: FlowStateStorageProvider = _

        override def getLocalStorageProvider = {
            if (localStorageProvider eq null)
                localStorageProvider = mock(classOf[FlowStateStorageProvider])
            localStorageProvider
        }
    }

    private def parseMockedTransferResponse(ctx: ChannelHandlerContext) = {
        val responseCaptor = ArgumentCaptor.forClass(classOf[ByteBuf])
        verify(ctx).writeAndFlush(responseCaptor.capture())
        val response = StateForPortResponse.parseFrom(responseCaptor.getValue.array())
        parseTransferResponse(response)
    }

    before {
        EmbeddedCassandraServerHelper.startEmbeddedCassandra(60000L)

        val flowStateConfig = ConfigFactory.parseString(
            s"""
               |zookeeper.zookeeper_hosts = "${zk.getConnectString}"
               |agent.minions.flow_state.enabled : true
               |agent.minions.flow_state.legacy_push_state : true
               |agent.minions.flow_state.legacy_read_state : true
               |agent.minions.flow_state.udp_port : 1234
               |agent.minions.flow_state.tcp_port : 4321
               |cassandra.servers : "127.0.0.1:9142"
               |cassandra.cluster : "midonet"
               |cassandra.replication_factor : 1
               |""".stripMargin)
        midolmanConfig = MidolmanConfig.forTests(flowStateConfig)
    }

    feature("Test service lifecycle") {
        scenario("A flow state storage object is created per thread.") {
            Given("A flow state service and message handler")
            val context = Context(UUID.randomUUID())
            val service = new FlowStateServiceTest(
                context, curator, executor, midolmanConfig)
            service.startAsync().awaitRunning(60, TimeUnit.SECONDS)

            val handler = service.getUdpMessageHandler
            And("Two threads that share the handler")
            class HandlingThread extends Thread {
                @volatile var storage: FlowStateStorageWriter = _
                @volatile var second_storage: FlowStateStorageWriter = _

                override def run: Unit = {
                    storage = handler.getLegacyStorage
                    second_storage = handler.getLegacyStorage
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

            service.stopAsync().awaitTerminated(10, TimeUnit.SECONDS)
        }

        scenario("Service starts, registers itself, and stops") {
            Given("A discovery service")
            And("A container service that is started")
            val context = Context(UUID.randomUUID())
            val service = new FlowStateServiceTest(
                context, curator, executor, midolmanConfig)
            service.startAsync().awaitRunning(60, TimeUnit.SECONDS)

            Then("The udp socket is bound on configured port")
            intercept[BindException] {
                new DatagramSocket(midolmanConfig.flowState.udpPort)
            }

            Then("The tcp socket is bound on configured port")
            intercept[BindException] {
                new ServerSocket(midolmanConfig.flowState.tcpPort)
            }

            When("The service is stopped")
            service.stopAsync().awaitTerminated(10, TimeUnit.SECONDS)

            Then("The udp port is unbound")
            new DatagramSocket(midolmanConfig.flowState.udpPort).close()

            Then("The tcp port is unbound")
            new ServerSocket(midolmanConfig.flowState.tcpPort).close()
        }

        scenario("Service is enabled in the default configuration schema") {
            Given("A flow state service that is started")
            val service = new FlowStateServiceTest(
                Context(UUID.randomUUID()), curator, executor, midolmanConfig)

            Then("The service is enabled")
            service.isEnabled shouldBe true
        }

    }

    feature("Message handling") {
        scenario("Service handle calls storage with a valid message") {
            Given("A flow state message handler and a valid message")
            val handler = new TestableInternalHandler(midolmanConfig.flowState)
            val (datagram, protos, _) = validFlowStateInternalMessage(
                numIngressPorts = 1, numEgressPorts = 1,
                numConntracks = 1, numNats = 1)

            When("The message is handled")
            handler.channelRead0(null, datagram)

            Then("The message received by the handler is sent to legacy storage")
            val mockedLegacyStorage = handler.getLegacyStorage
            verify(mockedLegacyStorage, times(1)).touchConnTrackKey(
                mockito.eq(protos.conntrackKeys.head),
                mockito.eq(protos.ingressPort), mockito.any())
            verify(mockedLegacyStorage, times(1)).touchNatKey(
                mockito.eq(protos.natKeys.head._1),
                mockito.eq(protos.natKeys.head._2),
                mockito.eq(protos.ingressPort), mockito.any())
            verify(mockedLegacyStorage, times(1)).submit()

            Then("The message received by the handler is saved in local storage")
            val mockedLocalStorage = handler.getLocalStorageProvider
            verify(mockedLocalStorage, times(1)).get(mockito.any())
        }

        scenario("Service handle calls storage with trace keys") {
            Given("A flow state handler and a message with trace keys")
            val handler = new TestableInternalHandler(midolmanConfig.flowState)
            val (datagram, protos, _) = validFlowStateInternalMessage(
                numIngressPorts = 1, numEgressPorts = 1,
                numConntracks = 1, numNats = 1, numTraces = 1)

            When("The message is handled")
            handler.channelRead0(null, datagram)

            Then("The message received by the handler is sent to legacy storage")
            val mockedLegacyStorage = handler.getLegacyStorage
            verify(mockedLegacyStorage, times(1)).touchConnTrackKey(
                mockito.eq(protos.conntrackKeys.head),
                mockito.eq(protos.ingressPort), mockito.any())
            verify(mockedLegacyStorage, times(1)).touchNatKey(
                mockito.eq(protos.natKeys.head._1),
                mockito.eq(protos.natKeys.head._2),
                mockito.eq(protos.ingressPort), mockito.any())
            verify(mockedLegacyStorage, times(1)).submit()

            Then("The message received by the handler is saved in local storage")
            val mockedLocalStorage = handler.getLocalStorageProvider
            verify(mockedLocalStorage, times(1)).get(mockito.any())
        }

        scenario("Service handle ignores non flow state sbe messages") {
            Given("A flow state message handler and an invalid message")
            val handler = new TestableInternalHandler(midolmanConfig.flowState)
            val datagram = invalidFlowStateMessage()

            When("the message is handled")
            handler.channelRead0(null, datagram)

            Then("The message is ignored in legacy storage")
            val mockedLegacyStorage = handler.getLegacyStorage
            verify(mockedLegacyStorage, times(0)).touchConnTrackKey(mockito.any(),
                                                              mockito.any(),
                                                              mockito.any())
            verify(mockedLegacyStorage, times(0)).touchNatKey(mockito.any(),
                                                        mockito.any(),
                                                        mockito.any(),
                                                        mockito.any())
            verify(mockedLegacyStorage, times(0)).submit()

            Then("The message is ignored in local storage")
            val mockedLocalStorage = handler.getLocalStorageProvider
            verify(mockedLocalStorage, times(0)).get(mockito.any())
        }

        scenario("Service handle calls storage with valid empty message") {
            Given("A flow state message handler and a message without keys")
            val handler = new TestableInternalHandler(midolmanConfig.flowState)
            val (datagram, protos, _) = validFlowStateInternalMessage(numIngressPorts = 0,
                                                              numEgressPorts = 0,
                                                              numConntracks = 0,
                                                              numNats = 0)

            When("The message is handled")
            handler.channelRead0(null, datagram)

            Then("The handler does not send any key to legacy storage")
            val mockedLegacyStorage = handler.getLegacyStorage
            verify(mockedLegacyStorage, times(0)).touchConnTrackKey(mockito.any(),
                                                              mockito.any(),
                                                              mockito.any())
            verify(mockedLegacyStorage, times(0)).touchNatKey(mockito.any(),
                                                        mockito.any(),
                                                        mockito.any(),
                                                        mockito.any())
            verify(mockedLegacyStorage, times(0)).submit()

            Then("The message is ignored in local storage")
            val mockedLocalStorage = handler.getLocalStorageProvider
            verify(mockedLocalStorage, times(0)).get(mockito.any())
        }

        scenario("Service handle calls to storage with > 1 keys") {
            Given("A flow state message handler and a message with > 1 keys")
            val handler = new TestableInternalHandler(midolmanConfig.flowState)
            val (datagram, protos, _) = validFlowStateInternalMessage(numConntracks = 2,
                                                              numNats = 2)
            When("The message is handled")
            handler.channelRead0(null, datagram)

            Then("The handler sends all keys to legacy storage")
            val mockedLegacyStorage = handler.getLegacyStorage
            verify(mockedLegacyStorage, times(2)).touchConnTrackKey(
                mockito.any(), mockito.eq(protos.ingressPort), mockito.any())
            verify(mockedLegacyStorage, times(2)).touchNatKey(
                mockito.any(), mockito.any(), mockito.eq(protos.ingressPort), mockito.any())
            verify(mockedLegacyStorage, times(1)).submit()

            Then("The message is saved in local storage")
            val mockedLocalStorage = handler.getLocalStorageProvider
            verify(mockedLocalStorage, times(1)).get(mockito.any())
        }

        scenario("Service remote handler receives valid flow state request") {
            Given("A flow state remote message handler")
            val handler = new TestableRemoteHandler()
            And("A valid transfer request")
            val request = validFlowStateTransferRequest

            When("The response is handled")
            val mockedCtx: ChannelHandlerContext = mock(classOf[ChannelHandlerContext])
            handler.channelRead0(mockedCtx, request)

            Then("The handler sends a response to the client Agent")
            verify(mockedCtx, times(1)).writeAndFlush(mockito.any())

            And("To respond the handler reads from local storage")
            val mockedLocalStorageProvider = handler.getLocalStorageProvider
            verify(mockedLocalStorageProvider, times(1)).get(mockito.any())

            And("The response sent is an Ack")
            val response = parseMockedTransferResponse(mockedCtx)
            assert(response.getClass == classOf[StateTransferAck])
        }

        scenario("Service remote handler receives invalid flow state request") {
            Given("A flow state remote message handler")
            val handler = new TestableRemoteHandler()
            And("An invalid transfer request")
            val request = invalidStateTranferRequest

            When("The response is handled")
            val mockedCtx = mock(classOf[ChannelHandlerContext])
            handler.channelRead0(mockedCtx, request)

            And("The handler doesn't read from local storage")
            val mockedLocalStorageProvider = handler.getLocalStorageProvider
            verify(mockedLocalStorageProvider, times(0)).get(mockito.any())

            Then("The handler sends a response to the client Agent")
            verify(mockedCtx, times(1)).writeAndFlush(mockito.any())

            And("The response sent is a BAD_REQUEST error")
            val response = parseMockedTransferResponse(mockedCtx)
            assert(response.getClass == classOf[StateTransferError] &&
                   response.asInstanceOf[StateTransferError].code == ErrorCode.BAD_REQUEST)
        }

        scenario("Service remote handler receives unknown flow state request") {
            Given("A flow state remote message handler")
            val handler = new TestableRemoteHandler()
            And("An invalid transfer request")
            val request = validFlowStateTransferRequest

            When("The response is handled")
            val mockedCtx = mock(classOf[ChannelHandlerContext])
            handler.channelRead0(mockedCtx, request)

            And("The handler tries to read from local storage")
            val mockedLocalStorageProvider = handler.getLocalStorageProvider
            verify(mockedLocalStorageProvider, times(1)).get(mockito.any())

            Then("The handler sends a response to the client Agent")
            verify(mockedCtx, times(1)).writeAndFlush(mockito.any())

            And("The response sent is a STORAGE_ERROR error")
            val response = parseMockedTransferResponse(mockedCtx)
            assert(response.getClass == classOf[StateTransferError] &&
                response.asInstanceOf[StateTransferError].code == ErrorCode.STORAGE_ERROR)
        }

    }
}

