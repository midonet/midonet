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


import java.io.File
import java.net.{BindException, DatagramSocket, ServerSocket}
import java.util.concurrent.{ExecutorService, TimeUnit}
import java.util.UUID

import com.google.common.io.Files
import com.typesafe.config.ConfigFactory
import io.netty.buffer.ByteBuf
import io.netty.channel.ChannelHandlerContext
import org.apache.curator.framework.CuratorFramework
import org.cassandraunit.utils.EmbeddedCassandraServerHelper
import org.junit.runner.RunWith
import org.midonet.cluster.flowstate.FlowStateTransfer.StateResponse
import org.midonet.cluster.storage.FlowStateStorageWriter
import org.mockito.Mockito.{atLeastOnce, mock, times, verify}
import org.mockito.{ArgumentCaptor, Matchers => mockito}
import org.scalatest.junit.JUnitRunner
import org.midonet.cluster.topology.TopologyBuilder
import org.midonet.cluster.util.CuratorTestFramework
import org.midonet.cluster.util.UUIDUtil.fromProto
import org.midonet.midolman.HostRequestProxy.FlowStateBatch
import org.midonet.midolman.config.MidolmanConfig
import org.midonet.minion.Context
import org.midonet.services.flowstate.handlers._
import org.midonet.services.flowstate.transfer.StateTransferProtocolParser.parseStateResponse
import org.midonet.services.flowstate.transfer.client._
import org.midonet.services.flowstate.transfer.internal._
import org.midonet.util.concurrent.SameThreadButAfterExecutorService
import org.midonet.util.io.stream.{ByteBufferBlockWriter, TimedBlockHeader}
import org.midonet.util.netty.ServerFrontEnd

@RunWith(classOf[JUnitRunner])
class FlowStateServiceTest extends FlowStateBaseTest
                                   with TopologyBuilder with CuratorTestFramework {

    private var midolmanConfig: MidolmanConfig = _
    private var midolmanConfigChangedPort: MidolmanConfig = _

    private val executor: ExecutorService = new SameThreadButAfterExecutorService
    private var internalClient: FlowStateInternalClient = _
    private var remoteClient: FlowStateRemoteClient = _
    private var tmpDir: File = _

    /** Mocked flow state minion, overrides local ip discovery */
    private class FlowStateServiceTest(nodeContext: Context, curator: CuratorFramework,
                                       executor: ExecutorService, config: MidolmanConfig)
        extends FlowStateService(nodeContext: Context, curator: CuratorFramework,
                                 executor: ExecutorService, config: MidolmanConfig) {

        override def startServerFrontEnds() = {
            port shouldBe 1234
            super.startServerFrontEnds()
        }

        def getUdpMessageHandler = writeMessageHandler
        def getTcpMessageHandler = readMessageHandler
    }

    private def parseMockedTransferResponse(ctx: ChannelHandlerContext) = {
        val responseCaptor = ArgumentCaptor.forClass(classOf[ByteBuf])
        // atLeastOnce is used because the first in the pipeline is the response
        // the next ones could be a state flow file, and if these exist then
        // the last one is the EOF but we don't care about them
        verify(ctx, atLeastOnce).writeAndFlush(responseCaptor.capture())
        val responses = responseCaptor.getAllValues
        val stateResponse = StateResponse.parseFrom(responses.get(0).array())
        parseStateResponse(stateResponse)
    }

    before {
        tmpDir = Files.createTempDir()
        // We assume midolman.log.dir contains an ending / but tmpdir does not
        // add it on some platforms.
        System.setProperty("minions.db.dir",
            s"${System.getProperty("java.io.tmpdir")}/")

        EmbeddedCassandraServerHelper.startEmbeddedCassandra(60000L)

        val config: String =
            s"""
               |zookeeper.zookeeper_hosts = "${zk.getConnectString}"
               |agent.minions.flow_state.enabled : true
               |agent.minions.flow_state.legacy_push_state : true
               |agent.minions.flow_state.legacy_read_state : true
               |agent.minions.flow_state.local_push_state : true
               |agent.minions.flow_state.local_read_state : true
               |agent.minions.flow_state.port : 1234
               |agent.minions.flow_state.connection_timeout : 5s
               |agent.minions.flow_state.block_size : 1
               |agent.minions.flow_state.blocks_per_port : 10
               |agent.minions.flow_state.expiration_time : 20s
               |agent.minions.flow_state.log_directory: ${tmpDir.getName}
               |cassandra.servers : "127.0.0.1:9142"
               |cassandra.cluster : "midonet"
               |cassandra.replication_factor : 1
               |"""

        val flowStateConfig = ConfigFactory.parseString(config.stripMargin)

        val flowStateConfigChangedPort = ConfigFactory.parseString(
            config.replace("1234", "1235").stripMargin)

        midolmanConfig = MidolmanConfig.forTests(flowStateConfig)
        midolmanConfigChangedPort = MidolmanConfig.forTests(flowStateConfigChangedPort)

        internalClient = new FlowStateInternalClient(midolmanConfig.flowState)
        remoteClient = new FlowStateRemoteClient(midolmanConfig.flowState)
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
                new DatagramSocket(midolmanConfig.flowState.port)
            }

            Then("The tcp socket is bound on configured port")
            intercept[BindException] {
                new ServerSocket(midolmanConfig.flowState.port)
            }

            When("The service is stopped")
            service.stopAsync().awaitTerminated(10, TimeUnit.SECONDS)

            Then("The udp port is unbound")
            new DatagramSocket(midolmanConfig.flowState.port).close()

            Then("The tcp port is unbound")
            new ServerSocket(midolmanConfig.flowState.port).close()
        }

        scenario("Service is enabled in the default configuration schema") {
            Given("A flow state service that is started")
            val service = new FlowStateServiceTest(
                Context(UUID.randomUUID()), curator, executor, midolmanConfig)

            Then("The service is enabled")
            service.isEnabled shouldBe true
        }

    }

    feature("Flow state write message handling") {
        scenario("Service handle calls storage with a valid message") {
            Given("A flow state message handler")
            val handler = new TestableWriteHandler(midolmanConfig.flowState)
            And("A valid message")
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
        }

        scenario("Service handle calls storage with trace keys") {
            Given("A flow state handler")
            val handler = new TestableWriteHandler(midolmanConfig.flowState)
            And("A message with trace keys")
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
            assert(handler.getWrites == 1)
        }

        scenario("Service handle ignores non flow state sbe messages") {
            Given("A flow state message handler")
            val handler = new TestableWriteHandler(midolmanConfig.flowState)
            And("An invalid message")
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
            assert(handler.getWrites == 0)
        }

        scenario("Service handle calls storage with valid empty message") {
            Given("A flow state message handler")
            val handler = new TestableWriteHandler(midolmanConfig.flowState)
            And("A message without keys")
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
            assert(handler.getWrites == 0)
        }

        scenario("Service handle calls to storage with > 1 keys") {
            Given("A flow state message handler and a message with > 1 keys")
            val handler = new TestableWriteHandler(midolmanConfig.flowState)
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
            assert(handler.getWrites == 1)
        }
    }

    feature("Flow state read message handling") {
        scenario("Service read handler receives valid raw state request") {
            Given("A flow state read message handler")
            val ports = createValidFlowStatePorts(midolmanConfig.flowState)
            val handler = new TestableReadHandler(midolmanConfig.flowState, ports)
            And("A valid raw state request")
            val request = rawStateRequest(handler.validPortId)

            When("The response is handled")
            val mockedCtx: ChannelHandlerContext = mock(classOf[ChannelHandlerContext])
            handler.channelRead0(mockedCtx, request)

            Then("The handler sends a response to the client Agent")
            verify(mockedCtx, times(3)).writeAndFlush(mockito.any())

            And("To respond the handler reads from local raw storage")
            assert(handler.getBufferReads == 1)

            And("The response sent is an Ack")
            val response = parseMockedTransferResponse(mockedCtx)
            assert(response.getClass == classOf[StateAck])
        }

        scenario("Service read handler receives valid remote state request") {
            Given("A flow state read message handler")
            val ports = createValidFlowStatePorts(midolmanConfig.flowState)
            val handler = new TestableReadHandler(midolmanConfig.flowState, ports)
            And("A valid remote state request")
            val request = remoteStateRequest(handler.validPortId)

            When("The response is handled")
            val mockedCtx: ChannelHandlerContext = mock(classOf[ChannelHandlerContext])
            handler.channelRead0(mockedCtx, request)

            Then("The handler sends a response to the client Agent")
            verify(mockedCtx, times(3)).writeAndFlush(mockito.any())

            And("To respond the handler reads from local state storage")
            assert(handler.getFlowStateReads == 1)

            And("The response sent is an Ack")
            val response = parseMockedTransferResponse(mockedCtx)
            assert(response.getClass == classOf[StateAck])
        }

        scenario("Service read handler receives valid internal state request") {
            Given("A flow state read message handler")
            val ports = createValidFlowStatePorts(midolmanConfig.flowState)
            val handler = new TestableReadHandler(midolmanConfig.flowState, ports)
            And("A valid internal state request")
            val request = internalStateRequest(handler.validPortId)

            When("The response is handled")
            val mockedCtx: ChannelHandlerContext = mock(classOf[ChannelHandlerContext])
            handler.channelRead0(mockedCtx, request)

            Then("The handler sends a response to the client Agent")
            verify(mockedCtx, times(3)).writeAndFlush(mockito.any())

            And("To respond the handler reads from local state storage")
            assert(handler.getFlowStateReads == 1)

            And("The response sent is an Ack")
            val response = parseMockedTransferResponse(mockedCtx)
            assert(response.getClass == classOf[StateAck])
        }

        scenario("Service read handler receives invalid flow state request") {
            Given("A flow state read message handler")
            val ports = createValidFlowStatePorts(midolmanConfig.flowState)
            val handler = new TestableReadHandler(midolmanConfig.flowState, ports)
            And("An invalid transfer request")
            val request = invalidStateTransferRequest

            When("The response is handled")
            val mockedCtx = mock(classOf[ChannelHandlerContext])
            handler.channelRead0(mockedCtx, request)

            And("The handler doesn't read from local storage")
            assert(handler.getFlowStateReads == 0 && handler.getBufferReads == 0)

            Then("The handler sends a response to the client Agent")
            verify(mockedCtx, times(1)).writeAndFlush(mockito.any())

            And("The response sent is a BAD_REQUEST error")
            val response = parseMockedTransferResponse(mockedCtx)
            assert(response.getClass == classOf[StateError] &&
                   response.asInstanceOf[StateError].code == ErrorCode.BAD_REQUEST)
        }

        scenario("Service read handler receives malformed flow state request") {
            Given("A flow state read message handler")
            val ports = createValidFlowStatePorts(midolmanConfig.flowState)
            val handler = new TestableReadHandler(midolmanConfig.flowState, ports)
            And("A malformed transfer request")
            val request = malformedStateTransferRequest

            When("The response is handled")
            val mockedCtx = mock(classOf[ChannelHandlerContext])
            handler.channelRead0(mockedCtx, request)

            And("The handler doesn't read from local storage")
            assert(handler.getFlowStateReads == 0 && handler.getBufferReads == 0)

            Then("The handler sends a response to the client Agent")
            verify(mockedCtx, times(1)).writeAndFlush(mockito.any())

            And("The response sent is a BAD_REQUEST error")
            val response = parseMockedTransferResponse(mockedCtx)
            assert(response.getClass == classOf[StateError] &&
                response.asInstanceOf[StateError].code == ErrorCode.BAD_REQUEST)
        }

        scenario("Service read handler receives unknown flow state request") {
            Given("A flow state read message handler")
            val ports = createValidFlowStatePorts(midolmanConfig.flowState)
            val handler = new TestableReadHandler(midolmanConfig.flowState, ports)
            And("An valid transfer request, but unknown to this agent")
            val request = internalStateRequest()

            When("The response is handled")
            val mockedCtx = mock(classOf[ChannelHandlerContext])
            handler.channelRead0(mockedCtx, request)

            And("The handler tries to read from local storage")
            assert(handler.getFlowStateReads == 1)

            Then("The handler sends an empty response to the client Agent")
            // One for the ack and one for the EOF, but no data
            verify(mockedCtx, times(2)).writeAndFlush(mockito.any())

            And("The response sent is an Ack")
            val response = parseMockedTransferResponse(mockedCtx)
            assert(response.getClass == classOf[StateAck])
        }

    }

    feature("Client-Server interaction between agents/flow state minions") {
        scenario("A raw flow state request between minions") {
            Given("A flow state read message handler")
            val ports = createValidFlowStatePorts(midolmanConfig.flowState)
            val handler = new TestableReadHandler(midolmanConfig.flowState, ports)
            And("A previous port id of the server agent")
            val portId = handler.validPortId
            And("A TCP server frontend handling requests")
            val server = ServerFrontEnd.tcp(handler, midolmanConfig.flowState.port)

            try {
                server.startAsync().awaitRunning(20, TimeUnit.SECONDS)

                When("The flow state is requested by the TCP client")
                val writer = mock(classOf[ByteBufferBlockWriter[TimedBlockHeader]])
                remoteClient.rawPipelinedFlowStateFrom("127.0.0.1",
                    fromProto(portId), writer)

                Then("The flow state for the given portId was received")
                verify(writer, times(1)).write(mockito.any())
            } finally {
                server.stopAsync().awaitTerminated(20, TimeUnit.SECONDS)
            }
        }

        scenario("A internal flow state request between minion and agent") {
            Given("A flow state read message handler")
            val ports = createValidFlowStatePorts(midolmanConfig.flowState)
            val handler = new TestableReadHandler(midolmanConfig.flowState, ports)
            And("A previous port id of the server agent")
            val portId = handler.validPortId
            And("A TCP server frontend handling requests")
            val server = ServerFrontEnd.tcp(handler, midolmanConfig.flowState.port)

            try {
                server.startAsync().awaitRunning(20, TimeUnit.SECONDS)

                When("The flow state is requested by the TCP client")
                val FlowStateBatch(sc, _, sn, _) =
                    internalClient.internalFlowStateFrom(fromProto(portId))

                Then("The flow state for the given portId was received")
                assert(!sc.isEmpty && !sn.isEmpty)
            } finally {
                server.stopAsync().awaitTerminated(20, TimeUnit.SECONDS)
            }
        }

        scenario("A remote flow state request between minion and agent") {
            Given("A flow state read message handler")
            val ports = Seq.empty
            // We need a new TCP port for the test, since the raw request that
            // will be chained from the initial remote request, will be on the
            // same server and we will need to bind to 2 different ports
            val handler = new TestableReadHandler(midolmanConfigChangedPort.flowState, ports)
            And("A TCP server frontend handling requests")
            val server = ServerFrontEnd.tcp(handler, midolmanConfig.flowState.port)
            And("A remote handler to simulate a remote Agent")
            val remotePorts = createValidFlowStatePorts(midolmanConfig.flowState)
            val remoteHandler = new TestableReadHandler(midolmanConfig.flowState, remotePorts)
            And("A remote TCP server frontend handling remote requests")
            val remoteServer = ServerFrontEnd.tcp(remoteHandler, midolmanConfigChangedPort.flowState.port)
            And("A previous port id of the remote agent")
            val portId = remoteHandler.validPortId

            try {
                server.startAsync()
                remoteServer.startAsync()

                server.awaitRunning(20, TimeUnit.SECONDS)
                remoteServer.awaitRunning(20, TimeUnit.SECONDS)

                When("The flow state is requested by the TCP client")
                val FlowStateBatch(sc, _, sn, _) =
                    internalClient.remoteFlowStateFrom("127.0.0.1", fromProto(portId))

                Then("The flow state for the given portId was received")
                assert(!sc.isEmpty && !sn.isEmpty)
            } finally {
                server.stopAsync().awaitTerminated(20, TimeUnit.SECONDS)
            }
        }

        scenario("An invalid flow state transfer between agents") {
            Given("A flow state read message handler")
            val ports = createValidFlowStatePorts(midolmanConfig.flowState)
            val handler = new TestableReadHandler(midolmanConfig.flowState, ports)
            And("An invalid port id")
            val portId = UUID.randomUUID()
            And("A TCP server frontend handling requests")
            val server = ServerFrontEnd.tcp(handler, midolmanConfig.flowState.port)

            try {
                server.startAsync().awaitRunning(20, TimeUnit.SECONDS)

                When("The flow state is requested by the TCP client")
                val FlowStateBatch(sc, _, sn, _) =
                    internalClient.internalFlowStateFrom(portId)

                Then("The flow state for the given portId was empty")
                assert(sc.isEmpty && sn.isEmpty)
            } finally {
                server.stopAsync().awaitTerminated(20, TimeUnit.SECONDS)
            }
        }
    }
}

