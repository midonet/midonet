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


import java.net.{BindException, DatagramSocket}
import java.util.concurrent.{ExecutorService, TimeUnit}
import java.util.{UUID}

import com.datastax.driver.core.Session
import com.typesafe.config.ConfigFactory

import org.apache.curator.framework.CuratorFramework
import org.cassandraunit.utils.EmbeddedCassandraServerHelper
import org.junit.runner.RunWith
import org.mockito.Mockito.{mock, times, verify}
import org.mockito.{Matchers => mockito}
import org.scalatest.junit.JUnitRunner

import org.midonet.cluster.storage.FlowStateStorageWriter
import org.midonet.cluster.topology.TopologyBuilder
import org.midonet.cluster.util.CuratorTestFramework
import org.midonet.midolman.config.MidolmanConfig
import org.midonet.minion.Context
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

        override def startServerFrontEnd() = {
            port shouldBe 1234
            super.startServerFrontEnd()
        }

        def getCassandraSession = cassandraSession

        def getMessageHandler = messageHandler

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

    before {
        EmbeddedCassandraServerHelper.startEmbeddedCassandra(60000L)

        val flowStateConfig = ConfigFactory.parseString(
            s"""
               |zookeeper.zookeeper_hosts = "${zk.getConnectString}"
               |agent.minions.flow_state.enabled : true
               |agent.minions.flow_state.port : 1234
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

            val handler = service.getMessageHandler
            And("Two threads that share the handler")
            class HandlingThread extends Thread {
                @volatile var storage: FlowStateStorageWriter = _
                @volatile var second_storage: FlowStateStorageWriter = _

                override def run: Unit = {
                    storage = handler.getStorage
                    second_storage = handler.getStorage
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

            Then("The socket is bound on configured port")
            intercept[BindException] {
                new DatagramSocket(midolmanConfig.flowState.port)
            }

            When("The service is stopped")
            service.stopAsync().awaitTerminated(10, TimeUnit.SECONDS)

            Then("The port is unbound")
            new DatagramSocket(midolmanConfig.flowState.port).close()
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
            val handler = new TestableStorageHandler(null)
            val (datagram, protos, _) = validFlowStateMessage(
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
            val (datagram, protos, _) = validFlowStateMessage(
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
            val datagram = invalidFlowStateMessage()

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
            val (datagram, protos, _) = validFlowStateMessage(numIngressPorts = 0,
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
            val (datagram, protos, _) = validFlowStateMessage(numConntracks = 2,
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

