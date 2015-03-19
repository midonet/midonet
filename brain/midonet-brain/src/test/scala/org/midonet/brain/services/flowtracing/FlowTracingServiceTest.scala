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

package org.midonet.brain.services.flowtracing

import java.util.{ArrayList, Date, List, UUID}
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

import scala.collection.JavaConversions._
import scala.collection.JavaConverters._
import scala.collection.mutable.{ListBuffer, Map}
import scala.concurrent.{Await, Promise}
import scala.concurrent.duration._
import scala.util.Random

import com.datastax.driver.core.utils.UUIDs
import org.scalatest._
import org.scalatest.concurrent.Eventually._
import org.scalatest.junit.JUnitRunner
import org.junit.runner.RunWith

import org.slf4j.LoggerFactory

import com.google.inject.Injector
import io.netty.channel.{Channel, ChannelHandlerContext, SimpleChannelInboundHandler}

import org.midonet.brain.ClusterNode
import org.midonet.brain.FlowTracingConfig
import org.midonet.cluster.data.Bridge.UNTAGGED_VLAN_ID
import org.midonet.cluster.flowtracing.FlowTracing._
import org.midonet.cluster.util.UUIDUtil._
import org.midonet.packets.MAC
import org.midonet.util.netty.{ClientFrontEnd, ProtoBufWebSocketClientAdapter}

@RunWith(classOf[JUnitRunner])
class FlowTracingServiceTest extends FeatureSpec with Matchers
        with BeforeAndAfter
        with GivenWhenThen {
    val log = LoggerFactory.getLogger(classOf[FlowTracingServiceTest])

    var injector: Injector = _
    var config: FlowTracingConfig = _
    var storage: MockFlowTracingStorage = _
    var service: FlowTracingService = _

    before {
        config = new MockConfig

        storage = new MockFlowTracingStorage
        service = new FlowTracingService(config, storage)

        service.start
    }

    scenario("Basic api test") {
        val trId1 = UUID.randomUUID
        val trId2 = UUID.randomUUID
        val ftrace1 = FlowTrace(UUIDs.timeBased, MAC.random(), MAC.random())
        val ftrace2 = FlowTrace(UUIDs.timeBased, MAC.random(), MAC.random())
        val ftrace3 = FlowTrace(UUIDs.timeBased, MAC.random(), MAC.random())

        storage.addTraces(trId1, ftrace1, 1, 10)
        storage.addTraces(trId2, ftrace2, 1, 20)
        storage.addTraces(trId1, ftrace3, 1, 10)

        val client = new FlowTracingTestClient("localhost", config.getPort)
        client.connect

        val txid = 1
        val req = Request.newBuilder().setTxnid(txid).setFlowCountRequest(
            FlowCountRequest.newBuilder()
                .setTraceRequestId(trId1).build()).build()

        client.sendRequest(req)
        val resp = client.readResponse

        resp should not be (null)
        resp.getTxnid should be (txid)

        resp.hasFlowCountResponse should be (true)
        resp.getFlowCountResponse.getCount should be (2)

        val tx2 = 2
        val req2 = Request.newBuilder().setTxnid(tx2).setFlowTracesRequest(
            FlowTracesRequest.newBuilder
                .setTraceRequestId(trId1).build).build
        client.sendRequest(req2)
        val resp2 = client.readResponse
        resp2 should not be (null)
        resp2.getTxnid should be (tx2)

        resp2.hasFlowTracesResponse should be (true)
        resp2.getFlowTracesResponse.getTraceList.size should be (2)

        val tx3 = 3
        val req3 = Request.newBuilder().setTxnid(tx3).setFlowTraceDataRequest(
            FlowTraceDataRequest.newBuilder.setTraceRequestId(trId1)
                .setFlowTraceId(ftrace1.id).build).build
        client.sendRequest(req3)
        val resp3 = client.readResponse
        resp3 should not be (null)
        resp3.getTxnid should be (tx3)
        resp3.hasFlowTraceDataResponse should be (true)
        val data = resp3.getFlowTraceDataResponse
        fromProto(data.getTrace.getId) should be (ftrace1.id)
        data.getTraceDataList.size should be (10)
    }

    class MockFlowTracingStorage extends FlowTracingStorageBackend {
        val traces = Map[(UUID, UUID), ListBuffer[(FlowTrace, Long, String)]]()

        def addTraces(traceRequestId: UUID, flowTrace: FlowTrace,
                      startTimestamp: Long, count: Int): Unit = {
            var ts = startTimestamp
            val list = traces.getOrElseUpdate(
                (traceRequestId, flowTrace.id),
                { ListBuffer[(FlowTrace, Long, String)]() })
            for (i <- 0 until count) {
                list += ((flowTrace, ts, "TraceData"+Random.nextInt()))
                ts += Random.nextInt(20)
            }
        }

        override def getFlowCount(traceRequestId: UUID,
                                  maxTime: Date, limit: Int): Long = {
            traces.keys.count(k => k._1 == traceRequestId)
        }

        override def getFlowTraces(traceRequestId: UUID,
                                   maxTime: Date,
                                   limit: Int): List[FlowTrace] = {
            log.info(s"get flow traces ${traceRequestId} ${maxTime} ${limit}")
            val keys = traces.keys.collect(
                { case k if k._1 == traceRequestId => k })
            var flowsToCollect = limit
            var flows = keys.flatMap({ traces.get(_) })
                .flatMap({ _.headOption })
                .collect({ case x if (x._2 <= maxTime.getTime &&
                                          flowsToCollect > 0)
                              => {
                                  flowsToCollect -= 1
                                  x._1
                              }
                         })
            log.info("flows {}", flows)
            new ArrayList(flows)
        }

        override def getFlowTraceData(traceRequestId: UUID, flowTraceId: UUID,
                                      maxTime: Date, limit: Int)
                : (FlowTrace, List[FlowTraceData]) = {
            val ftraces = traces.getOrElse((traceRequestId, flowTraceId),
                                           { throw new TraceNotFoundException })
            val ftrace = ftraces.headOption.getOrElse(
                { throw new TraceNotFoundException })._1

            var tracesToCollect = limit
            val host = UUID.randomUUID

            val data = ftraces.collect(
                { case (_, ts, data) if (tracesToCollect > 0) => {
                     tracesToCollect -= 1
                     FlowTraceData(host, ts, data)
                 }
                })
            (ftrace, data.asJava)
        }
    }

    class MockConfig extends FlowTracingConfig(null) {
        override def isEnabled = true
        override def getPort = 12346
    }

}

class FlowTracingTestClient(host: String, port: Int)
        extends SimpleChannelInboundHandler[Response] {
    val log = LoggerFactory.getLogger(classOf[FlowTracingTestClient])

    var channel = Promise[Channel]

    val handler = new ProtoBufWebSocketClientAdapter(this,
                                           Response.getDefaultInstance,
                                           FlowTracingService.WebSocketPath)
    val client = new ClientFrontEnd(
        handler,
        host, port)

    val responseQueue = new ArrayBlockingQueue[Response](10)

    def connect: Unit = {
        log.info("Connecting ")
        client.startAsync.awaitRunning
    }

    def sendRequest(request: Request): Unit = {
        val channel = Await.result(this.channel.future, 10 seconds)
        Await.result(handler.checkHandshake(channel), 10 seconds)

        val f = channel.writeAndFlush(request)
        f.awaitUninterruptibly()
    }

    def readResponse(): Response =
        responseQueue.poll(10, TimeUnit.SECONDS)

    override def channelActive(ctx: ChannelHandlerContext): Unit = {
        channel.success(ctx.channel)
    }

    override def channelRead0(ctx: ChannelHandlerContext,
                              msg: Response):
            Unit = {
        log.info("received something {}", msg)
        responseQueue.put(msg)
    }

}
