/*
 * Copyright 2015 Midokura SARL
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
package org.midonet.midolman.monitoring

import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit
import java.util.{UUID, Map => JMap}

import scala.collection.JavaConverters._
import scala.concurrent.duration.Duration

import com.google.common.io.BaseEncoding
import com.google.common.net.HostAndPort

import org.codehaus.jackson.map.ObjectMapper
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import rx.Observer

import io.netty.channel.socket.SocketChannel
import io.netty.channel.{ChannelHandlerContext, ChannelInitializer, SimpleChannelInboundHandler}
import io.netty.handler.codec.bytes.ByteArrayDecoder
import io.netty.handler.codec.protobuf.ProtobufVarint32FrameDecoder

import org.midonet.cluster.flowhistory._
import org.midonet.cluster.services.MidonetBackend
import org.midonet.midolman.PacketWorkflow
import org.midonet.midolman.PacketWorkflow.SimulationResult
import org.midonet.midolman.config.{FlowHistoryConfig, MidolmanConfig}
import org.midonet.midolman.rules.RuleResult
import org.midonet.midolman.simulation.PacketContext
import org.midonet.midolman.util.MidolmanSpec
import org.midonet.odp.flows._
import org.midonet.odp.{FlowMatch, Packet}
import org.midonet.packets.util.PacketBuilder._
import org.midonet.packets.{IPv4Addr, MAC}
import org.midonet.sdn.flows.FlowTagger
import org.midonet.util.netty.ServerFrontEnd
import org.midonet.util.reactivex.TestAwaitableObserver

@RunWith(classOf[JUnitRunner])
class FlowRecorderTest extends MidolmanSpec {
    import FlowRecorderTest.{EndpointServiceName, Timeout}

    feature("flow recording construction") {
        scenario("unconfigured flow history yields null recorder") {
            val (recorder, _) = createRecorder(config)
            recorder shouldBe a [NullFlowRecorder]
        }
        scenario("Record matches with null fields don't throw exceptions") {
            try {
                FlowRecordBuilder.buildRecord(
                    UUID.randomUUID(),
                    new PacketContext,
                    null)
            } catch {
                case npe: NullPointerException => fail(npe)
            }
        }
    }

    feature("abstract flow recorder") {
        scenario("flow recorder endpoint in discovery before start") {
            val target = HostAndPort.fromString("192.0.2.0:12345")
            val confStr =
                s"""
                  |agent.flow_history.enabled=true
                  |agent.flow_history.encoding=none
                  |agent.flow_history.endpoint_service="$EndpointServiceName"
                """.stripMargin
            val conf = MidolmanConfig.forTests(confStr)
            val (recorder, discovery) = createTestRecorder(conf)
            discovery.registerServiceInstance(EndpointServiceName, target)

            recorder.startAsync().awaitRunning()

            recorder.endpoint.get shouldBe hpToSocketAddress(target)

            recorder.stopAsync().awaitTerminated()
        }
        scenario("flow recorder endpoint in discovery after start") {
            val target = HostAndPort.fromString("192.0.2.0:12345")
            val confStr =
                s"""
                  |agent.flow_history.enabled=true
                  |agent.flow_history.encoding=none
                  |agent.flow_history.endpoint_service="$EndpointServiceName"
                """.stripMargin
            val conf = MidolmanConfig.forTests(confStr)
            val (recorder, discovery) = createTestRecorder(conf)

            recorder.startAsync().awaitRunning()

            recorder.endpoint shouldBe None
            discovery.registerServiceInstance(EndpointServiceName,
                                              target)
            recorder.endpoint.get shouldBe hpToSocketAddress(target)

            recorder.stopAsync().awaitTerminated()
        }
        scenario("flow recorder adapts to changes in discovery") {
            val target1 = HostAndPort.fromString("192.0.1.0:12345")
            val target2 = HostAndPort.fromString("192.0.2.0:12345")
            val confStr =
                s"""
                  |agent.flow_history.enabled=true
                  |agent.flow_history.encoding=none
                  |agent.flow_history.endpoint_service="$EndpointServiceName"
                """.stripMargin
            val conf = MidolmanConfig.forTests(confStr)
            val (recorder, discovery) = createTestRecorder(conf)

            recorder.startAsync().awaitRunning()

            // Register target1
            val t1Handle = discovery.registerServiceInstance(
                EndpointServiceName, target1)
            // Register target2
            val t2Handle = discovery.registerServiceInstance(
                EndpointServiceName, target2)

            val bothTargetAddresses =
                List(target1, target2).map(hpToSocketAddress).toSet

            val targetHandlers =
                bothTargetAddresses.zip(List(t1Handle, t2Handle)).toMap

            // Chosen endpoint must be one of the 2
            bothTargetAddresses.contains(recorder.endpoint.get) shouldBe true

            val expectedFinalTarget =
                (bothTargetAddresses - recorder.endpoint.get).head

            // Remove the active endpoint from discovery
            targetHandlers(recorder.endpoint.get).unregister()

            // Should now have the other endpoint as the target
            recorder.endpoint.get shouldBe expectedFinalTarget

            // Remove the active endpoint from discovery
            targetHandlers(recorder.endpoint.get).unregister()

            // Should now have no endpoints
            recorder.endpoint shouldBe None

            recorder.stopAsync().awaitTerminated()
        }
        scenario("invalid endpoint doesn't throw error on record()") {
            val confStr =
                s"""
                  |agent.flow_history.enabled=true
                  |agent.flow_history.encoding=none
                  |agent.flow_history.endpoint_service="$EndpointServiceName"
                """.stripMargin
            val conf = MidolmanConfig.forTests(confStr)
            val (recorder, discovery) = createTestRecorder(conf)

            recorder.startAsync().awaitRunning()

            val target = HostAndPort.fromString("fake-fake-fake-fake:12345")
            discovery.registerServiceInstance(EndpointServiceName, target)
            recorder.endpoint.get shouldBe hpToSocketAddress(target)
            recorder.record(newContext(), PacketWorkflow.NoOp)

            recorder.stopAsync().awaitTerminated()
        }
        scenario("unreachable endpoint doesn't throw error on record()") {
            val confStr =
                s"""
                |agent.flow_history.enabled=true
                |agent.flow_history.encoding=none
                |agent.flow_history.endpoint_service="$EndpointServiceName"
                """.stripMargin
            val conf = MidolmanConfig.forTests(confStr)
            val (recorder, discovery) = createTestRecorder(conf)

            recorder.startAsync().awaitRunning()

            val target = HostAndPort.fromString("192.0.2.0:12345")
            discovery.registerServiceInstance(EndpointServiceName, target)
            recorder.endpoint.get shouldBe hpToSocketAddress(target)
            recorder.record(newContext(), PacketWorkflow.NoOp)

            recorder.stopAsync().awaitTerminated()
        }
        scenario("exception in encodeRecord doesn't propagate") {
            val confStr =
                s"""
                |agent.flow_history.enabled=true
                |agent.flow_history.encoding=none
                |agent.flow_history.endpoint_service="$EndpointServiceName"
                """.stripMargin
            val conf = MidolmanConfig.forTests(confStr)
            val (recorder, discovery) = createErrorRecorder(conf)

            recorder.startAsync().awaitRunning()

            val target = HostAndPort.fromString("192.0.2.0:12345")
            discovery.registerServiceInstance(EndpointServiceName, target)
            recorder.endpoint.get shouldBe hpToSocketAddress(target)
            recorder.record(newContext(), PacketWorkflow.NoOp)

            recorder.stopAsync().awaitTerminated()
        }
    }

    feature("JSON flow recoder") {
        scenario("correct values are shipped, nulls are not") {
            val target = HostAndPort.fromString("localhost:50022")
            val confStr =
                s"""
                |agent.flow_history.enabled=true
                |agent.flow_history.encoding=json
                |agent.flow_history.endpoint_service="$EndpointServiceName"
                """.stripMargin
            val conf = MidolmanConfig.forTests(confStr)

            val (recorder, discovery) = createRecorder(conf)

            recorder.startAsync().awaitRunning()

            discovery.registerServiceInstance(EndpointServiceName, target)

            val observer = new TestAwaitableObserver[Array[Byte]]

            val srv = getDelimBytesServer(50022, observer)

            srv.startAsync().awaitRunning(Timeout.toMillis,
                                          TimeUnit.MILLISECONDS)

            val ctx = newContext()
            recorder.record(ctx, PacketWorkflow.NoOp)
            try {
                observer.awaitOnNext(1, Timeout) shouldBe true
                val data = observer.getOnNextEvents.asScala.head

                val mapper = new ObjectMapper()

                val result: JMap[String, Object] =
                    mapper.readValue(new String(data),
                                     classOf[JMap[String,Object]])

                val origMatch = ctx.origMatch
                result.get("flowMatch.networkSrc") should be (
                    BaseEncoding.base16.encode(origMatch.getNetworkSrcIP.toBytes))
                result.get("flowMatch.networkDst") should be (
                    BaseEncoding.base16.encode(origMatch.getNetworkDstIP.toBytes))
                result.get("flowMatch.ethSrc") should be (
                    BaseEncoding.base16.encode(origMatch.getEthSrc.getAddress()))
                result.get("flowMatch.ethDst") should be (
                    BaseEncoding.base16.encode(origMatch.getEthDst.getAddress()))

                for (e <- result.entrySet.asScala) {
                    log.info(s"${e.getKey} => ${e.getValue}")
                    e.getValue should not be (null)
                }
            } finally {
                srv.stopAsync().awaitTerminated(Timeout.toMillis,
                                                TimeUnit.MILLISECONDS)
                recorder.stopAsync().awaitTerminated()
            }
        }
    }

    feature("Binary flow records") {
        scenario("data is encoded/decoded correctly") {
            val target = HostAndPort.fromString("localhost:50023")

            val confStr =
                s"""
                |agent.flow_history.enabled=true
                |agent.flow_history.encoding=binary
                |agent.flow_history.endpoint_service="$EndpointServiceName"
                """.stripMargin
            val conf = MidolmanConfig.forTests(confStr)

            val (recorder, discovery) = createRecorder(conf)

            recorder.startAsync().awaitRunning()

            discovery.registerServiceInstance(EndpointServiceName,
                                              target)

            val observer = new TestAwaitableObserver[Array[Byte]]

            val srv = getDelimBytesServer(50023, observer)

            srv.startAsync().awaitRunning(Timeout.toMillis,
                                          TimeUnit.MILLISECONDS)

            val binSerializer = new BinarySerialization
            try {
                val ctx1 = newContext()
                recorder.record(ctx1, PacketWorkflow.NoOp)
                val ctx2 = newContext()
                recorder.record(ctx2, PacketWorkflow.GeneratedPacket)

                observer.awaitOnNext(2, Timeout) shouldBe true

                val receivedData = observer.getOnNextEvents.asScala

                receivedData.size shouldBe 2

                val data1 = receivedData.head

                val shouldMatch1 = FlowRecordBuilder.buildRecord(
                    recorder.asInstanceOf[BinaryFlowRecorder].hostId,
                    ctx1, PacketWorkflow.NoOp)
                shouldMatch1 should be (binSerializer.bufferToFlowRecord(data1))

                val data2 = receivedData(1)

                val shouldMatch2 = FlowRecordBuilder.buildRecord(
                    recorder.asInstanceOf[BinaryFlowRecorder].hostId,
                    ctx2, PacketWorkflow.GeneratedPacket)

                shouldMatch2 should be (binSerializer.bufferToFlowRecord(data2))
            } finally {
                srv.stopAsync().awaitTerminated(Timeout.toMillis,
                                                TimeUnit.MILLISECONDS)
                recorder.stopAsync().awaitTerminated()
            }
        }
        scenario("Flooding packet is dropped, no exception is generated") {

            val confStr =
                """
                  |agent.flow_history.enabled=true
                  |agent.flow_history.encoding=binary
                """.stripMargin
            val conf = MidolmanConfig.forTests(confStr)

            val (recorder, discovery) = createRecorder(conf)

            recorder.startAsync().awaitRunning()

            discovery.registerServiceInstance(EndpointServiceName,
                                              "localhost:50023")

            val maxNumberOfDevices =  BinarySerialization.BufferSize / 17;
            val ctx1 = newContext(2 * maxNumberOfDevices)
            noException should be thrownBy {
                recorder.record(ctx1, PacketWorkflow.NoOp)
            }

            recorder.stopAsync().awaitTerminated()
        }

        scenario("Packet that cannot be send as single datagram is dropped") {

            val confStr =
                """
                  |agent.flow_history.enabled=true
                  |agent.flow_history.encoding=binary
                """.stripMargin
            val conf = MidolmanConfig.forTests(confStr)

            val (recorder, discovery) = createRecorder(conf)

            recorder.startAsync().awaitRunning()

            discovery.registerServiceInstance(EndpointServiceName,
                                              "localhost:50023")

            val maxNumberOfDevices =  BinarySerialization.BufferSize / 17;
            val ctx1 = newContext(maxNumberOfDevices / 5)
            noException should be thrownBy {
                recorder.record(ctx1, PacketWorkflow.NoOp)
            }

            recorder.stopAsync().awaitTerminated()
        }
    }

    private def newContext(numPorts: Int = 5): PacketContext = {
        val ethernet = { eth addr MAC.random -> MAC.random } <<
            { ip4 addr IPv4Addr.random --> IPv4Addr.random } <<
            { icmp.unreach.host }
        val wcmatch = new FlowMatch(FlowKeys.fromEthernetPacket(ethernet))
        val packet = new Packet(ethernet, wcmatch)
        val ctx = PacketContext.generated(0, packet, wcmatch)
        ctx.inPortId = UUID.randomUUID

        for (i <- 1.until(numPorts)) {
            ctx.addFlowTag(FlowTagger.tagForPort(UUID.randomUUID))
        }
        for (i <- 1.until(10)) {
            val ruleResult = new RuleResult(RuleResult.Action.DROP)
            val ruleId = UUID.randomUUID()
            ctx.recordTraversedRule(ruleId, ruleResult)
            ctx.recordMatchedRule(ruleId, true)
            ctx.recordAppliedRule(ruleId, true)
        }
        for (i <- 1.until(3)) {
            ctx.outPorts.add(UUID.randomUUID)
        }
        for (i <- 1.until(6)) {
            ctx.flowActions.add(FlowActions.randomAction)
        }
        ctx
    }

    /**
      * Create a server frontend expecting to receive delimited byte buffers.
      *
      * Delimited byte buffers are bytebuffers prepended by a varint field
      * containing the length of the buffer.
      *
      * @param port Port where server should listen to.
      * @param observer Observer to notify of received byte arrays.
      * @return Server that notifies some observer of received delimited byte
      *         arrays.
      */
    private def getDelimBytesServer(port: Int, observer: Observer[Array[Byte]])
    : ServerFrontEnd =
        ServerFrontEnd.tcp(
            new ChannelInitializer[SocketChannel]() {
                override def initChannel(ch: SocketChannel): Unit = {
                    ch.pipeline.addLast(new ProtobufVarint32FrameDecoder)
                    ch.pipeline.addLast(new ByteArrayDecoder)

                    ch.pipeline().addLast(
                        new SimpleChannelInboundHandler[Array[Byte]]() {
                            override def channelRead0(ctx: ChannelHandlerContext,
                                                      msg: Array[Byte]) = {
                                observer.onNext(msg)
                            }
                        }
                    )
                }
            },
            port
        )

    private def backend: MidonetBackend =
        injector.getInstance(classOf[MidonetBackend])

    private def createRecorder(config: MidolmanConfig) = {
        val recorder = FlowRecorder(config, hostId, backend)

        (recorder, backend.discovery)
    }

    private def createTestRecorder(config: MidolmanConfig) = {
        val recorder = new TestFlowRecorder(config.flowHistory, backend)

        (recorder, backend.discovery)
    }

    private def createErrorRecorder(config: MidolmanConfig) = {
        val recorder = new ErrorFlowRecorder(config.flowHistory, backend)

        (recorder, backend.discovery)
    }

    private def hpToSocketAddress(hp: HostAndPort) =
        new InetSocketAddress(hp.getHostText, hp.getPort)


    class TestFlowRecorder(conf: FlowHistoryConfig,
                           backend: MidonetBackend)
            extends AbstractFlowRecorder(conf, backend) {
        val buffer = ByteBuffer.allocate(0)
        override def encodeRecord(pktContext: PacketContext,
                                  simRes: SimulationResult): ByteBuffer = {
            buffer
        }
    }

    class ErrorFlowRecorder(conf: FlowHistoryConfig,
                            backend: MidonetBackend)
            extends AbstractFlowRecorder(conf, backend) {
        override def encodeRecord(pktContext: PacketContext,
                                  simRes: SimulationResult): ByteBuffer = {
            throw new RuntimeException("foobar")
        }
    }
}

object FlowRecorderTest {
    final val EndpointServiceName = "cliotest"
    final val Timeout = Duration("5s")
}
