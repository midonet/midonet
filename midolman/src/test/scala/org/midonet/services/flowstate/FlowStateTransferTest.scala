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

import java.nio.ByteBuffer
import java.nio.file.{Files, Paths}
import java.util.UUID
import java.util.concurrent.TimeUnit

import scala.collection.JavaConverters._

import org.junit.runner.RunWith
import org.mockito.Mockito.{atLeastOnce, mock, times, verify}
import org.mockito.{ArgumentCaptor, Matchers => mockito}
import org.scalatest.junit.JUnitRunner

import org.midonet.cluster.topology.TopologyBuilder
import org.midonet.midolman.HostRequestProxy.FlowStateBatch
import org.midonet.midolman.config.MidolmanConfig
import org.midonet.services.flowstate.handlers._
import org.midonet.services.flowstate.stream.{ByteBufferBlockReader, Context, FlowStateBlock, FlowStateManager}
import org.midonet.services.flowstate.transfer.client._
import org.midonet.util.io.stream.{ByteBufferBlockWriter, TimedBlockHeader}
import org.midonet.util.netty.ServerFrontEnd

@RunWith(classOf[JUnitRunner])
class FlowStateTransferTest extends FlowStateBaseTest with TopologyBuilder {

    private var config: MidolmanConfig = _
    private var configChangedPort: MidolmanConfig = _
    private var configAlt: MidolmanConfig = _

    private var streamContext: Context = _
    private var streamContextChangedPort: Context = _
    private var streamContextAlt: Context = _

    private var internalClient: FlowStateInternalClient = _
    private var remoteClient: FlowStateRemoteClient = _

    private var ports: Seq[UUID] = _
    private var handler: TestableReadHandler = _
    private var server: ServerFrontEnd = _

    private def currentRawState(context: Context, portId: UUID) = {
        var raw = new collection.mutable.ArrayBuffer[Byte]()
        val in = ByteBufferBlockReader(context, portId)
        val headerBuff = new Array[Byte](FlowStateBlock.headerSize)
        val blockBuff = new Array[Byte](context.config.blockSize)

        in.read(headerBuff)
        var header = FlowStateBlock(ByteBuffer.wrap(headerBuff))
        var next = in.read(blockBuff, 0, header.blockLength)

        while (next > 0) {
            raw ++= headerBuff
            raw ++= blockBuff.slice(0, next)

            in.read(headerBuff)
            header = FlowStateBlock(ByteBuffer.wrap(headerBuff))
            next = in.read(blockBuff, 0, header.blockLength)
        }

        raw
    }

    private def allWrittenBytes(writer: ByteBufferBlockWriter[TimedBlockHeader]) = {
        val responseCaptor = ArgumentCaptor.forClass(classOf[Array[Byte]])
        verify(writer, atLeastOnce).write(responseCaptor.capture())
        responseCaptor.getAllValues.asScala.flatten
    }

    before {
        // We assume midolman.log.dir contains an ending / but tmpdir does not
        // add it on some platforms.
        System.setProperty("minions.db.dir",
                           s"${System.getProperty("java.io.tmpdir")}/")

        config = MidolmanConfig.forTests(getConfig(cleanDelay = 20))
        configChangedPort = MidolmanConfig.forTests(getConfig(cleanDelay = 20))
        configAlt = MidolmanConfig.forTests(getConfig(cleanDelay = 20))
        val manager = new FlowStateManager(config.flowState)
        val managerChangedPort = new FlowStateManager(configChangedPort.flowState)
        val managerAlt = new FlowStateManager(configAlt.flowState)

        streamContext = Context(config.flowState,
                                manager)
        streamContextChangedPort = Context(configChangedPort.flowState,
                                           managerChangedPort)
        streamContextAlt = Context(configAlt.flowState,
                                   managerAlt)

        internalClient = new FlowStateInternalClient(configAlt.flowState)
        remoteClient = new FlowStateRemoteClient(configAlt.flowState)

        ports = createValidFlowStatePorts(streamContextAlt)
        handler = new TestableReadHandler(streamContextAlt, ports)
        server = ServerFrontEnd.tcp(handler, configAlt.flowState.port)
        server.startAsync().awaitRunning(20, TimeUnit.SECONDS)
    }

    after {
        server.stopAsync().awaitTerminated(20, TimeUnit.SECONDS)
    }

    feature("Client-Server interaction between agents/flow state minions") {
        scenario("A raw flow state request between minions") {
            Given("A previous port id of the server agent")
            val portId = handler.validPortId
            And("The raw state for the port")
            val initialRaw = currentRawState(streamContextAlt, portId)

            When("The flow state is requested by the TCP client")
            val writer = mock(classOf[ByteBufferBlockWriter[TimedBlockHeader]])
            remoteClient.rawPipelinedFlowStateFrom("127.0.0.1", portId, writer)

            Then("The flow state for the given portId was received")
            verify(writer, times(1)).write(mockito.any())
            And("The received raw response maintained its integrity")
            val receivedRaw = allWrittenBytes(writer)
            receivedRaw shouldBe initialRaw
        }

        scenario("A internal flow state request between minion and agent") {
            Given("A previous port id of the server agent")
            val portId = handler.validPortId

            When("The flow state is requested by the TCP client")
            val FlowStateBatch(sc, _, sn, _) =
                internalClient.internalFlowStateFrom(portId)

            Then("The flow state for the given portId was received")
            sc should not be empty
            sn should not be empty
        }

        scenario("A remote flow state request between minion and agent") {
            Given("A flow state read message handler")
            val localPorts = Seq.empty
            val internalClient = new FlowStateInternalClient(config.flowState)
            val localHandler = new TestableReadHandler(
                streamContextChangedPort, localPorts)
            And("A TCP server frontend handling requests")
            val localServer = ServerFrontEnd.tcp(localHandler,
                                                 config.flowState.port)
            And("A remote handler to simulate a remote Agent")
            val remotePorts = createValidFlowStatePorts(streamContext)
            val remoteHandler = new TestableReadHandler(streamContext,
                                                        remotePorts)
            And("A remote TCP server frontend handling remote requests")
            val remoteServer = ServerFrontEnd.tcp(remoteHandler,
                                                  configChangedPort.flowState.port)
            And("A previous port id of the remote agent")
            val portId = remoteHandler.validPortId
            And("The file on the remote host should exist")
            val fsFileName = s"${streamContext.ioManager.storageDirectory}/$portId"
            Files.exists(Paths.get(fsFileName)) shouldBe true

            try {
                localServer.startAsync()
                remoteServer.startAsync()
                localServer.awaitRunning(20, TimeUnit.SECONDS)
                remoteServer.awaitRunning(20, TimeUnit.SECONDS)

                When("The flow state is requested by the TCP client")
                val FlowStateBatch(sc, _, sn, _) =
                    internalClient.remoteFlowStateFrom("127.0.0.1", portId)

                Then("The flow state for the given portId was received")
                sc should not be empty
                sn should not be empty

                And("The file on the remote side was deleted")
                Files.exists(Paths.get(fsFileName)) shouldBe false

            } finally {
                localServer.stopAsync()
                remoteServer.stopAsync()
                localServer.awaitTerminated(20, TimeUnit.SECONDS)
                remoteServer.awaitTerminated(20, TimeUnit.SECONDS)
            }
        }

        scenario("An invalid flow state transfer between agents") {
            Given("An invalid port id")
            val portId = UUID.randomUUID()

            When("The flow state is requested by the TCP client")
            val FlowStateBatch(sc, _, sn, _) =
                internalClient.internalFlowStateFrom(portId)

            Then("The flow state for the given portId was empty")
            sc shouldBe empty
            sn shouldBe empty
        }
    }
}

