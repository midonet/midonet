/*
 * Copyright 2014 Midokura SARL
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

package org.midonet.brain.services.topology.server

import java.util.ArrayList
import java.util.UUID
import java.util.concurrent.{TimeUnit, CountDownLatch}

import scala.collection.JavaConverters._
import scala.concurrent.{Promise, Future}
import scala.util.Random

import com.google.protobuf.Message

import io.netty.channel.{ChannelHandlerContext, ChannelFuture, ChannelFutureListener}

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest.{FeatureSpec, Matchers}
import org.slf4j.LoggerFactory

import rx.functions.Action1
import rx.{Observable, Subscription, Observer}
import rx.subjects.{ReplaySubject, Subject}

import org.midonet.cluster.models.{Topology, Commons}
import org.midonet.cluster.rpc.Commands
import org.midonet.cluster.services.topology.common._
import org.midonet.cluster.services.topology.server.RequestHandler
import org.midonet.cluster.util.UUIDUtil
import org.midonet.util.functors.makeAction0
import org.midonet.util.netty.{ProtoBufWebSocketAdapter, ProtoBufSocketAdapter, ClientFrontEnd, ServerFrontEnd}


@RunWith(classOf[JUnitRunner])
class ServerFrontEndTest extends FeatureSpec with Matchers {

    def genUUID(msb: Long, lsb: Long): Commons.UUID =
        UUIDUtil.toProto(new UUID(msb, lsb))

    def genAck(id: Commons.UUID): Message =
        Commands.Response.newBuilder().setAck(
            Commands.Response.Ack.newBuilder().setReqId(id).build()
        ).build()

    def genHandshake(reqId: Commons.UUID, cnxId: Commons.UUID): Message =
        Commands.Request.newBuilder().setHandshake(
            Commands.Request.Handshake.newBuilder()
                .setReqId(reqId)
                .setCnxnId(cnxId)
                .build()
        ).build()

    def genBye(reqId: Commons.UUID): Message =
        Commands.Request.newBuilder().setBye(
            Commands.Request.Bye.newBuilder()
                .setReqId(reqId)
                .build()
        ).build()

    def genGet(reqId: Commons.UUID, t: Topology.Type): Message =
        Commands.Request.newBuilder().setGet(
            Commands.Request.Get.newBuilder()
                .setReqId(reqId)
                .setType(t)
                .build()
        ).build()

    def genUnsubscribe(reqId: Commons.UUID, t: Topology.Type): Message =
        Commands.Request.newBuilder().setUnsubscribe(
            Commands.Request.Unsubscribe.newBuilder()
                .setReqId(reqId)
                .setType(t)
                .build()
        ).build()

    val protocol = new ProtocolFactory {
        private val log = LoggerFactory.getLogger(classOf[ProtocolFactory])
        class Receiving(observer: Observer[Message]) extends State {
            override def process(msg: Any): State = msg match {
                case msg: Commands.Request if msg.hasHandshake =>
                    observer.onNext(genAck(msg.getHandshake.getReqId))
                    log.debug("protocol msg: {}", msg)
                    this
                case msg: Commands.Request if msg.hasBye =>
                    observer.onNext(genAck(msg.getBye.getReqId))
                    observer.onCompleted()
                    log.debug("protocol msg: {}", msg)
                    this
                case msg: Commands.Request if msg.hasGet =>
                    observer.onNext(genAck(msg.getGet.getReqId))
                    log.debug("protocol msg: {}", msg)
                    this
                case msg: Commands.Request if msg.hasUnsubscribe =>
                    observer.onNext(genAck(msg.getUnsubscribe.getReqId))
                    log.debug("protocol msg: {}", msg)
                    this
                case Interruption =>
                    observer.onCompleted()
                    log.debug("protocol msg: {}", msg)
                    this
                case other =>
                    log.debug("protocol msg: {}", other)
                    this
            }
        }
        override def start(output: Observer[Message]):
            (State, Future[Option[Subscription]]) =
            (new Receiving(output),
                Promise[Option[Subscription]]().success(None).future)
    }

    feature("plain socket server")
    {
        val port = Random.nextInt(100) + 8000
        val connMgr = new ConnectionManager(protocol)
        val expected = Commands.Request.getDefaultInstance

        scenario("service life cycle") {
            val reqHandler = new RequestHandler(connMgr)
            val handler = new ApiServerHandler(reqHandler)
            val srv = new ServerFrontEnd(new ProtoBufSocketAdapter(handler, expected),
                                         port)

            srv.startAsync().awaitRunning()
            srv.isRunning should be (true)
            srv.stopAsync().awaitTerminated()
            srv.isRunning should be (false)
        }
    }

    feature("plain socket server communication")
    {
        val port = Random.nextInt(100) + 8000
        val host = "localhost"

        val connMgr = new ConnectionManager(protocol)
        val serverExpected = Commands.Request.getDefaultInstance
        val clientExpected = Commands.Response.getDefaultInstance

        val messages = List(
            genHandshake(genUUID(0, 1), UUIDUtil.toProto(UUID.randomUUID())),
            genGet(genUUID(0, 2), Topology.Type.NETWORK),
            genUnsubscribe(genUUID(0, 3), Topology.Type.NETWORK),
            genBye(genUUID(0, 4))
        )

        val responses = List(
            genAck(genUUID(0, 1)),
            genAck(genUUID(0, 2)),
            genAck(genUUID(0, 3)),
            genAck(genUUID(0, 4))
        )

        abstract class clientEngine extends Observer[CommEvent] {
            def observable: Observable[Commands.Response]
            def awaitCompletion(): Unit
        }

        def newClient = new clientEngine {
            private val log = LoggerFactory.getLogger(classOf[clientEngine])
            private val lck: CountDownLatch = new CountDownLatch(1)
            private val out: Subject[Commands.Response, Commands.Response] =
                ReplaySubject.create()
            private val obs: Observable[Commands.Response] =
                out.asObservable.doOnCompleted(makeAction0 {
                    lck.countDown()
                    log.debug("COMPLETED")
                })
            private var channel: ChannelHandlerContext = null
            private var sendNext = 1
            private val sender = new ChannelFutureListener {
                override def operationComplete(p1: ChannelFuture): Unit = {
                    log.debug("sender: operation completed")
                    if (sendNext < messages.length) {
                        log.debug("sender: sending next message")
                        val msg = messages(sendNext); sendNext += 1
                        channel.writeAndFlush(msg).addListener(this)
                    }
                }
            }
            override def observable = obs
            override def awaitCompletion() = lck.await(10, TimeUnit.SECONDS)
            override def onError(exc: Throwable): Unit = out.onCompleted()
            override def onCompleted(): Unit = out.onCompleted()
            override def onNext(ev: CommEvent): Unit = ev match {
                case Connect(ctx) =>
                    log.debug("sending messages")
                    channel = ctx
                    channel.writeAndFlush(messages(0)).addListener(sender)
                case Response(ctx, rsp) =>
                    log.debug("response: {}", rsp)
                    out.onNext(rsp)
                case Disconnect(ctx) =>
                    log.debug("disconnection: {}", ctx)
                    out.onCompleted()
                case other =>
                    log.debug("unexpected reception: {}", other)
            }
        }

        scenario("server accepting all requests") {
            val reqHandler = new RequestHandler(connMgr)
            val handler = new ApiServerHandler(reqHandler)
            val srv = new ServerFrontEnd(
                new ProtoBufSocketAdapter(handler, serverExpected), port)
            srv.startAsync().awaitRunning()
            srv.isRunning should be (true)

            val answers: java.util.List[Commands.Response] = new ArrayList()
            val client = newClient
            val subs = client.observable.subscribe(new Action1[Commands.Response] {
                override def call(ev: Commands.Response): Unit = answers.add(ev)
            })

            val clienthandler = new ApiClientHandler(client)
            val cli = new ClientFrontEnd(
                new ProtoBufSocketAdapter(clienthandler, clientExpected), host, port)

            cli.startAsync().awaitRunning()
            cli.isRunning() should be (true)

            client.awaitCompletion()

            cli.stopAsync().awaitTerminated()
            srv.stopAsync().awaitTerminated()

            subs.unsubscribe()

            answers.size() should be (4)
            responses.zip(answers.asScala)
                .forall(x => {x._1 == x._2}) should be (true)
        }

    }

    feature("websocket-based server")
    {
        val port = Random.nextInt(100) + 8000
        val path = "/websocket"
        val connMgr = new ConnectionManager(protocol)
        val expected = Commands.Request.getDefaultInstance

        scenario("service life cycle") {
            val reqHandler = new RequestHandler(connMgr)
            val handler = new ApiServerHandler(reqHandler)
            val srv = new ServerFrontEnd(
                new ProtoBufWebSocketAdapter(handler, expected, path), port)

            srv.startAsync().awaitRunning()
            srv.isRunning should be (true)
            srv.stopAsync().awaitTerminated()
            srv.isRunning should be (false)
       }
    }
}


