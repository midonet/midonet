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
package org.midonet.midolman.monitoring

import java.io.IOException
import java.net.InetSocketAddress
import java.nio.{BufferOverflowException, ByteBuffer}
import java.util.concurrent.TimeUnit

import scala.collection.JavaConverters._
import scala.concurrent.duration.Duration
import scala.util.Random

import com.google.common.net.HostAndPort

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import rx.Observer

import io.netty.channel.socket.SocketChannel
import io.netty.channel.{ChannelHandlerContext, ChannelInitializer, SimpleChannelInboundHandler}
import io.netty.handler.codec.bytes.ByteArrayDecoder
import io.netty.handler.codec.protobuf.ProtobufVarint32FrameDecoder

import org.midonet.cluster.flowhistory.BinarySerialization
import org.midonet.cluster.services.MidonetBackend
import org.midonet.midolman.config.MidolmanConfig
import org.midonet.midolman.util.MidolmanSpec
import org.midonet.util.netty.ServerFrontEnd
import org.midonet.util.reactivex.TestAwaitableObserver

@RunWith(classOf[JUnitRunner])
class FlowSenderWorkerTest extends MidolmanSpec {
    import FlowSenderWorkerTest.{EndpointServiceName, Timeout}

    feature("flow sender worker construction") {
        scenario("unconfigured flow history yields null worker") {
            val (worker, _) = createWorker(config)
            worker shouldBe NullFlowSenderWorker
        }
        scenario("non-active flow history yields null worker") {
            val confStr =
                s"""
                   |agent.flow_history.enabled=false
                   |agent.flow_history.endpoint_service="$EndpointServiceName"
                """.stripMargin
            val conf = MidolmanConfig.forTests(confStr)
            val (worker, _) = createWorker(conf)
            worker shouldBe NullFlowSenderWorker
        }
        scenario("active flow history with no target yields null worker") {
            val confStr =
                s"""
                   |agent.flow_history.enabled=true
                   |agent.flow_history.endpoint_service=""
                """.stripMargin
            val conf = MidolmanConfig.forTests(confStr)
            val (worker, _) = createWorker(conf)
            worker shouldBe NullFlowSenderWorker
        }
        scenario("active flow history with target yields non-null worker") {
            val confStr =
                s"""
                   |agent.flow_history.enabled=true
                   |agent.flow_history.endpoint_service="$EndpointServiceName"
                """.stripMargin
            val conf = MidolmanConfig.forTests(confStr)
            val (worker, _) = createWorker(conf)
            worker should not be NullFlowSenderWorker
        }
    }

    feature("flow sender") {
        scenario("flow sender endpoint in discovery before start") {
            val target = HostAndPort.fromString("192.0.2.0:12345")
            val confStr =
                s"""
                  |agent.flow_history.endpoint_service="$EndpointServiceName"
                """.stripMargin
            val conf = MidolmanConfig.forTests(confStr)
            val (sender, discovery) = createFlowSender(conf)
            discovery.registerServiceInstance(EndpointServiceName, target)

            sender.startAsync().awaitRunning()

            sender.endpoint.get shouldBe hpToSocketAddress(target)

            sender.stopAsync().awaitTerminated()
        }
        scenario("flow sender endpoint in discovery after start") {
            val target = HostAndPort.fromString("192.0.2.0:12345")
            val confStr =
                s"""
                  |agent.flow_history.endpoint_service="$EndpointServiceName"
                """.stripMargin
            val conf = MidolmanConfig.forTests(confStr)
            val (sender, discovery) = createFlowSender(conf)

            sender.startAsync().awaitRunning()

            sender.endpoint shouldBe None
            discovery.registerServiceInstance(EndpointServiceName,
                                              target)
            sender.endpoint.get shouldBe hpToSocketAddress(target)

            sender.stopAsync().awaitTerminated()
        }
        scenario("flow sender adapts to changes in discovery") {
            val target1 = HostAndPort.fromString("192.0.1.0:12345")
            val target2 = HostAndPort.fromString("192.0.2.0:12345")
            val confStr =
                s"""
                  |agent.flow_history.endpoint_service="$EndpointServiceName"
                """.stripMargin
            val conf = MidolmanConfig.forTests(confStr)
            val (sender, discovery) = createFlowSender(conf)

            sender.startAsync().awaitRunning()

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
            bothTargetAddresses.contains(sender.endpoint.get) shouldBe true

            val expectedFinalTarget =
                (bothTargetAddresses - sender.endpoint.get).head

            // Remove the active endpoint from discovery
            targetHandlers(sender.endpoint.get).unregister()

            // Should now have the other endpoint as the target
            sender.endpoint.get shouldBe expectedFinalTarget

            // Remove the active endpoint from discovery
            targetHandlers(sender.endpoint.get).unregister()

            // Should now have no endpoints
            sender.endpoint shouldBe None

            sender.stopAsync().awaitTerminated()
        }
        scenario("invalid endpoint doesn't throw error on onEvent()") {
            val confStr =
                s"""
                  |agent.flow_history.endpoint_service="$EndpointServiceName"
                """.stripMargin
            val conf = MidolmanConfig.forTests(confStr)
            val (sender, discovery) = createFlowSender(conf)

            sender.startAsync().awaitRunning()

            val target = HostAndPort.fromString("fake-fake-fake-fake:12345")
            discovery.registerServiceInstance(EndpointServiceName, target)
            sender.endpoint.get shouldBe hpToSocketAddress(target)

            val buffer = ByteBuffer.allocate(0)

            noException should be thrownBy sender.onEvent(buffer, 0,
                                                          endOfBatch=true)

            sender.stopAsync().awaitTerminated()
        }
        scenario("unreachable endpoint doesn't throw error on record()") {
            val confStr =
                s"""
                |agent.flow_history.endpoint_service="$EndpointServiceName"
                """.stripMargin
            val conf = MidolmanConfig.forTests(confStr)
            val (sender, discovery) = createFlowSender(conf)

            sender.startAsync().awaitRunning()

            val target = HostAndPort.fromString("192.0.2.0:12345")
            discovery.registerServiceInstance(EndpointServiceName, target)
            sender.endpoint.get shouldBe hpToSocketAddress(target)

            val buffer = ByteBuffer.allocate(0)

            noException should be thrownBy sender.onEvent(buffer, 0,
                                                          endOfBatch=true)

            sender.stopAsync().awaitTerminated()
        }
        scenario("exception in onEvent doesn't propagate") {
            val confStr =
                s"""
                |agent.flow_history.endpoint_service="$EndpointServiceName"
                """.stripMargin
            val conf = MidolmanConfig.forTests(confStr)
            val (sender, discovery) = createErrorFlowSender(conf)

            sender.startAsync().awaitRunning()

            val target = HostAndPort.fromString("192.0.2.0:12345")
            discovery.registerServiceInstance(EndpointServiceName, target)
            sender.endpoint.get shouldBe hpToSocketAddress(target)

            val buffer = ByteBuffer.allocate(0)

            noException should be thrownBy sender.onEvent(buffer, 0,
                                                          endOfBatch=true)

            sender.stopAsync().awaitTerminated()
        }
    }


    feature("Disruptor flow sender worker") {
        scenario("messages sent correctly") {
            val target = HostAndPort.fromString("localhost:50023")

            val confStr =
                s"""
                |agent.flow_history.enabled=true
                |agent.flow_history.endpoint_service="$EndpointServiceName"
                """.stripMargin
            val conf = MidolmanConfig.forTests(confStr)

            val (worker, discovery) = createWorker(conf)

            worker.startAsync().awaitRunning()

            discovery.registerServiceInstance(EndpointServiceName,
                                              target)

            val observer = new TestAwaitableObserver[Array[Byte]]

            val srv = getDelimBytesServer(50023, observer)

            srv.startAsync().awaitRunning(Timeout.toMillis,
                                          TimeUnit.MILLISECONDS)

            try {
                val buf1 = randomBytes(Random.nextInt(400) + 1)
                val buf2 = randomBytes(Random.nextInt(400) + 1)

                worker.submit(ByteBuffer.wrap(buf1))
                worker.submit(ByteBuffer.wrap(buf2))

                observer.awaitOnNext(2, Timeout) shouldBe true

                val receivedData = observer.getOnNextEvents.asScala

                receivedData.size shouldBe 2

                val data1 = receivedData.head

                data1 shouldBe buf1

                val data2 = receivedData(1)

                data2 shouldBe buf2
            } finally {
                srv.stopAsync().awaitTerminated(Timeout.toMillis,
                                                TimeUnit.MILLISECONDS)
                worker.stopAsync().awaitTerminated()
            }
        }
        scenario("too big messages should throw exception") {
            val target = HostAndPort.fromString("localhost:50024")

            val confStr =
                s"""
                   |agent.flow_history.enabled=true
                   |agent.flow_history.endpoint_service="$EndpointServiceName"
                """.stripMargin
            val conf = MidolmanConfig.forTests(confStr)

            val (worker, discovery) = createWorker(conf)

            worker.startAsync().awaitRunning()

            discovery.registerServiceInstance(EndpointServiceName,
                                              target)

            val observer = new TestAwaitableObserver[Array[Byte]]

            val srv = getDelimBytesServer(50024, observer)

            srv.startAsync().awaitRunning(Timeout.toMillis,
                                          TimeUnit.MILLISECONDS)

            try {
                val buf = randomBytes(BinarySerialization.BufferSize + 1)

                a [BufferOverflowException] should be thrownBy
                    worker.submit(ByteBuffer.wrap(buf))
            } finally {
                srv.stopAsync().awaitTerminated(Timeout.toMillis,
                                                TimeUnit.MILLISECONDS)
                worker.stopAsync().awaitTerminated()
            }
        }
        scenario("messages sent after discovery change") {
            val target1 = HostAndPort.fromString("localhost:50025")
            val target2 = HostAndPort.fromString("localhost:50026")

            val confStr =
                s"""
                   |agent.flow_history.enabled=true
                   |agent.flow_history.endpoint_service="$EndpointServiceName"
                """.stripMargin
            val conf = MidolmanConfig.forTests(confStr)

            val (worker, discovery) = createWorker(conf)

            worker.startAsync().awaitRunning()

            val target1Handler =
                discovery.registerServiceInstance(EndpointServiceName, target1)

            val observer1 = new TestAwaitableObserver[Array[Byte]]
            val srv1 = getDelimBytesServer(50025, observer1)

            val observer2 = new TestAwaitableObserver[Array[Byte]]
            val srv2 = getDelimBytesServer(50026, observer2)

            srv1.startAsync().awaitRunning(Timeout.toMillis,
                                           TimeUnit.MILLISECONDS)

            srv2.startAsync().awaitRunning(Timeout.toMillis,
                                           TimeUnit.MILLISECONDS)

            try {
                val buf1 = randomBytes(Random.nextInt(400) + 1)
                val buf2 = randomBytes(Random.nextInt(400) + 1)

                worker.submit(ByteBuffer.wrap(buf1))

                observer1.awaitOnNext(1, Timeout) shouldBe true

                val receivedData1 = observer1.getOnNextEvents.asScala

                receivedData1.size shouldBe 1

                val data1 = receivedData1.head

                data1 shouldBe buf1

                // Unregister target1 and register target2
                target1Handler.unregister()
                discovery.registerServiceInstance(EndpointServiceName, target2)

                worker.submit(ByteBuffer.wrap(buf2))

                observer2.awaitOnNext(1, Timeout) shouldBe true

                val receivedData2 = observer2.getOnNextEvents.asScala

                receivedData2.size shouldBe 1

                val data2 = receivedData2.head

                data2 shouldBe buf2
            } finally {
                srv1.stopAsync().awaitTerminated(Timeout.toMillis,
                                                 TimeUnit.MILLISECONDS)
                srv2.stopAsync().awaitTerminated(Timeout.toMillis,
                                                 TimeUnit.MILLISECONDS)
                worker.stopAsync().awaitTerminated()
            }
        }
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

    private def createWorker(config: MidolmanConfig) = {
        val recorder = FlowSenderWorker(config, backend)

        (recorder, backend.discovery)
    }

    private def createFlowSender(config: MidolmanConfig) = {
        val discovery = backend.discovery
        val flowSender = new FlowSender(config.flowHistory, discovery)
        (flowSender, discovery)
    }

    private def createErrorFlowSender(config: MidolmanConfig) = {
        val discovery = backend.discovery
        val flowSender = new FlowSender(config.flowHistory, discovery) {
            override protected def sendRecord(buffer: ByteBuffer): Unit =
                throw new IOException("Error!")
        }
        (flowSender, discovery)
    }

    private def hpToSocketAddress(hp: HostAndPort) =
        new InetSocketAddress(hp.getHostText, hp.getPort)

    private def randomBytes(length: Int): Array[Byte] = {
        val bytes = new Array[Byte](length)
        Random.nextBytes(bytes)
        bytes
    }
}

object FlowSenderWorkerTest {
    final val EndpointServiceName = "cliotest"
    final val Timeout = Duration("5s")
}
