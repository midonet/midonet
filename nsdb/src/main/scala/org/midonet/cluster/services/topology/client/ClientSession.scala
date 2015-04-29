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

package org.midonet.cluster.services.topology.client

import java.util.UUID
import java.util.concurrent.Executor
import java.util.concurrent.atomic.{AtomicInteger, AtomicReference, AtomicLong}

import scala.collection.concurrent.TrieMap
import scala.concurrent.duration.Duration
import scala.concurrent.{Await, ExecutionContext, Future, Promise}
import scala.util.{Try, Failure, Success}

import com.google.common.util.concurrent.Service
import com.google.common.util.concurrent.Service.Listener
import io.netty.channel.Channel
import org.slf4j.LoggerFactory
import rx.subjects.{PublishSubject, Subject}
import rx.{Observable, Observer}

import org.midonet.cluster.models.{Commons, Topology}
import org.midonet.cluster.rpc.Commands
import org.midonet.cluster.rpc.Commands.ResponseType
import org.midonet.cluster.services.topology.common._
import org.midonet.cluster.util.UUIDUtil
import org.midonet.util.functors.makeAction0
import org.midonet.util.netty.{ProtoBufWebSocketClientAdapter, ClientFrontEnd, ProtoBufWebSocketAdapter, ProtoBufSocketAdapter}

/**
 * A client session for the Topology service. It publishes an observable
 * (returned by the 'connect' method) where the updates and deletions from
 * the Midonet topology are pushed (according to the client requests). If
 * the connection is lost (i.e. the observable is closed) unexpectedly, the
 * client can try to recover the same session in the server (maintaining
 * existing subscriptions) by creating a new ClientSession and specifying
 * the same sessionId as the interrupted one, and the lastEventSeqno seen
 * as a 'startAt' position.
 *
 * The life-cycle uses 'promises' to indicate states; it goes as follows:
 * - Initially: the netty client service is stopped
 * - on observable subscribe: (initiated by the ClientSession caller)
 *                            the netty client service is started and a plain
 *                            socket connection will be created, generating
 *                            a Connect(ctx) event
 * - on Connect(ctx) event: (received from Netty)
 *                          wait for low-level protocol (e.g. websocket)
 *                          handshake, then satisfy the 'connected' promise
 * - on 'connected' promise: (generated internally on reception of the
 *                           'low-level handshake completed' event)
 *                           send the topology API service protobuf handshake,
 *                           and wait for ack.
 * - on protobuf handshake ack: (received via netty from the Topology Service)
 *                              satisfy the 'handshake' promise and emit any
 *                              standing and new requests, processing the
 *                              corresponding ack/nack messages, if any.
 *
 * - on client-initiated shutdown: send bye request, which should generate a
 *                                 network disconnection
 *
 * - on network disconnection: (received from netty)
 *                             satisfy the 'closed' promise.
 * - on 'closed' promise: (generated internally)
 *                        complete update observable, clean-up pending requests
 * - on update observable completion: (generated internally)
 *                                    stop netty service
 * - on netty service stop: satisfy the 'stopped' promise
 *
 *
 * @param host is the service host
 * @param port is the service port
 * @param wspath is the websocket url path, or null for a non-ws connection
 * @param sessionId is the id for this session (may be forced to a certain
 *                  value to recover a disconnected session).
 * @param startAt is the first event that should be retrieved from a lost
 *                session (usually, the last even before disconnection)
 */
class ClientSession(val host: String, val port: Int, val wspath: String,
                    val sessionId: UUID = UUID.randomUUID(),
                    val startAt: Long = 0,
                    val senderFactory: MessageSenderFactory
                    = MessageSender)
    extends Observer[CommEvent] {

    def this(host: String, port: Int) =
        this(host, port, null, UUID.randomUUID(), 0)
    def this(host: String, port: Int, sessionId: UUID) =
        this(host, port, null, sessionId, 0)
    def this(host: String, port: Int, sessionId: UUID, startAt: Long) =
        this(host, port, null, sessionId, startAt)

    import ClientSession._

    private val log =
        LoggerFactory.getLogger("org.midonet.topology-ClientSession")

    // Execute continuations on the completing thread
    private implicit val atSameThread = new Executor {
        override def execute(runnable: Runnable): Unit = runnable.run()
    }
    private implicit val ec = ExecutionContext.fromExecutor(atSameThread)

    private val lastSeqno = new AtomicLong(0)

    // Define the stuff needed to handle netty pipelines
    private val handler = new ApiClientHandler(this)
    private val adapter = if (wspath == null || wspath.isEmpty)
        new ProtoBufSocketAdapter(handler, Commands.Response.getDefaultInstance)
    else
        new ProtoBufWebSocketClientAdapter(handler,
                                           Commands.Response.getDefaultInstance,
                                           wspath)
    private val srv = new ClientFrontEnd(adapter, host, port)

    private def channelReady(ch: Channel): Future[Channel] = adapter match {
        case ws: ProtoBufWebSocketAdapter[_] => ws.checkHandshake(ch)
        case _ => Future.successful(ch)
    }

    // Handle abstract service governing the netty connections
    private val stopped = Promise[Boolean]()
    srv.addListener(new Listener {
        override def terminated(from: Service.State) = stopped.trySuccess(true)
        override def failed(from: Service.State, err: Throwable) = {
            stopped.tryFailure(err)
            closed.tryFailure(err)
        }
    }, atSameThread)
    private val service = new AtomicInteger(0)
    private def startService() = {
        if (service.incrementAndGet() == 1) {
            Try(srv.startAsync())
        }
    }
    private def stopService() = try {
        log.debug("stopping client front-end service")
        srv.stopAsync().awaitTerminated()
    } catch {
        case e: Throwable => log.warn("failed to stop client front-end", e)
    }


    // Identify Ack/Nack messages from the Topology API service protocol
    private def parse(msg: Commands.Response): Option[(Commons.UUID, Boolean)] =
        msg match {
            case m: Commands.Response if m.getType == ResponseType.ACK =>
                Some((m.getReqId, true))
            case m: Commands.Response if m.getType == ResponseType.NACK =>
                Some((m.getReqId, false))
            case _ => None
        }

    // Handle protocol-related promises

    // 'connected' is triggered once the netty connection is established
    // and the low-level protocol (e.g. websockets) handshake is completed.
    // It triggers the Topology API Service handshake.
    private val connected = Promise[MessageSender]()
    connected.future.onSuccess {case ctx => try {
        log.debug("connection established: " + sessionId)
        val (id, req) = newHandshake()
        handshakeId.set(id)
        log.debug("sending handshake: " + req)
        ctx.sendAndFlushNow(req)
    } catch {
        case exc: Throwable => handshake.tryFailure(exc)
    }}

    // 'handshake' is triggered once the Topology API handshake is completed
    // It allows for Topology API requests to be sent to server
    private val handshakeId = new AtomicReference[Commons.UUID](null)
    private val handshake = Promise[Boolean]()
    handshake.future.onComplete {
        case Success(_) => log.info("handshake complete")
        case Failure(exc) => log.warn("handshake failed: " + sessionId, exc)
    }

    // 'closed' is triggered on network disconnection
    // It starts cleanup procedures, including updates observable completion,
    // which in turn triggers netty service stop.
    private val closed = Promise[Boolean]()
    closed.future.onComplete(exit => {
        exit match {
            case Success(_) => updateStream.onCompleted()
            case Failure(exc) => updateStream.onError(exc)
        }
        pending.keys.map(pending.remove).flatten.map(_.tryComplete(exit))
    })

    // a map with the promises corresponding to on-the-fly requests. They are
    // completed on reception of the corresponding ack/nack.
    private val pending = new TrieMap[Commons.UUID, Promise[Boolean]]()

    // Process communication events received from netty
    override protected def onNext(ev: CommEvent): Unit = ev match {
        case Connect(ctx) =>
            channelReady(ctx.channel()).onComplete({
                case Success(ch) =>
                    log.info("underlying protocol handshake completed")
                    connected.trySuccess(senderFactory.get(ctx, handshake.future))
                case Failure(err) =>
                    log.warn("underlying protocol handshake failed")
                    connected.tryFailure(err)
            })
        case Disconnect(ctx) =>
            closed.trySuccess(true)
        case Error(ctx, exc) =>
            closed.tryFailure(exc)
        case Response(ctx, proto) if !handshake.isCompleted =>
            log.debug("checking for handshake ack: " + proto)
            if (proto.hasSeqno) lastSeqno.set(proto.getSeqno)
            parse(proto) match {
                case Some((id, true)) if id.equals(handshakeId.get) =>
                    log.debug("received successful handshake message")
                    handshake.trySuccess(true)
                case Some((id, false)) if id.equals(handshakeId.get) =>
                    log.debug("received failed handshake message")
                    handshake.tryFailure(
                        new ClientHandshakeRejectedException(
                            s"$sessionId at $startAt"))
                case other =>
                    log.debug("received non-handshake message: " + other)
            }
        case Response(ctx, proto) => parse(proto) match {
            case Some((id, true)) =>
                log.debug("received ack: " + id)
                pending.remove(id).map{_.trySuccess(true)}
            case Some((id, false)) =>
                log.debug("received nack: " + id)
                pending.remove(id).map{_.tryFailure(
                    new ClientCommandRejectedException(
                        UUIDUtil.fromProto(id).toString))}
            case _ =>
                // check piggy-backed ack
                if (proto.hasReqId)
                    pending.remove(proto.getReqId).map{_.trySuccess(true)}
                updateStream.onNext(proto)
        }
    }
    override protected def onCompleted(): Unit = {
        log.warn("session terminated unexpectedly: " + sessionId)
        closed.trySuccess(false)
    }
    override protected def onError(exc: Throwable): Unit = {
        log.error("session connection broken: " + sessionId, exc)
        closed.tryFailure(exc)
    }

    // Observable providing the user with the topology update and deletion
    // information. It activates the netty service on subscription, and stops
    // the netty service on completion.
    private val updateStream: Subject[Commands.Response, Commands.Response] =
        PublishSubject.create()
    private val updateOutput = updateStream.asObservable()
        .doOnTerminate(makeAction0 {stopService()})
        .doOnSubscribe(makeAction0 {startService()})


    //
    // Utility methods to generate protobuf-based requests
    //

    private def newHandshake(): (Commons.UUID, Commands.Request) = {
        val id = UUIDUtil.randomUuidProto
        val req = Commands.Request.newBuilder().setHandshake(
            Commands.Request.Handshake.newBuilder()
                .setReqId(id)
                .setCnxnId(UUIDUtil.toProto(sessionId))
                .build()
        ).build()
        (id, req)
    }

    private def newBye(): (Commons.UUID, Commands.Request) = {
        val id = UUIDUtil.randomUuidProto
        val req = Commands.Request.newBuilder().setBye(
            Commands.Request.Bye.newBuilder()
                .setReqId(id)
                .build()
        ).build()
        (id, req)
    }

    private def newGet(tp: Topology.Type, watch: Boolean, oid: Commons.UUID):
                (Commons.UUID, Commands.Request) = {
        val id = UUIDUtil.randomUuidProto
        val getRequest = Commands.Request.Get.newBuilder()
            .setReqId(id)
            .setType(tp)
            .setSubscribe(watch)
        if (oid != null)
            getRequest.setId(oid)
        val req = Commands.Request.newBuilder().setGet(
            getRequest.build()
        ).build()
        (id, req)
    }

    private def newUnsubs(tp: Topology.Type, oid: Commons.UUID):
                (Commons.UUID, Commands.Request) = {
        val id = UUIDUtil.randomUuidProto
        val unsubsRequest = Commands.Request.Unsubscribe.newBuilder()
            .setReqId(id)
            .setType(tp)
        if (oid != null)
            unsubsRequest.setId(oid)
        val req = Commands.Request.newBuilder().setUnsubscribe(
            unsubsRequest.build()
        ).build()
        (id, req)
    }

    //
    // Public interface
    //

    /**
     * Get an update stream that will start emitting data on first subscribe
     */
    def connect(): Observable[Commands.Response] = updateOutput

    /**
     * Terminate the session and disconnect from server
     */
    def terminate(): Future[Boolean] = {
        if (service.incrementAndGet() == 1) {
            closed.success(true)
            stopped.success(true)
            connected.tryFailure(
                new ClientCommandRejectedException("session terminated"))
        } else {
            val (reqId, bye) = newBye()
            val futureResp = Promise[Boolean]()
            pending.put(reqId, futureResp)
            log.debug("sending command (bye): " + bye)
            connected.future.onSuccess {case ctx => ctx.sendAndFlush(bye)}
        }
        closed.future
    }

    def terminateNow(): Future[Boolean] = {
        if (service.incrementAndGet() == 1) {
            closed.success(true)
            stopped.success(true)
            connected.tryFailure(
                new ClientCommandRejectedException("session terminated"))
        } else {
            stopService()
        }
        closed.future
    }

    /**
     * Wait for connection termination
     */
    def awaitTermination(atMost: Duration): Option[Throwable] = {
        val done = closed.future.flatMap(_ => stopped.future)
        Await.ready(done, atMost).value.get match {
            case Failure(cause) => Some(cause)
            case Success(_) => None
        }
    }

    private def command(reqPair: (Commons.UUID, Commands.Request))
        : RequestState = {
        val futureResp = Promise[Boolean]()
        val (reqId, req) = reqPair
        pending.put(reqId, futureResp)
        log.debug("sending command: {}", req)
        connected.future.onSuccess {case ctx => ctx.sendAndFlush(req)}
        RequestState(reqId, futureResp.future)
    }

    /**
     * Retrieve the last state of a specific object
     */
    def get(tp: Topology.Type, id: Commons.UUID): RequestState =
        command(newGet(tp, watch = false, id))

    /**
     * Retrieve the set of ids of the objects of a given type
     */
    def getAll(tp: Topology.Type): RequestState =
        command(newGet(tp, watch = false, null))

    /**
     * Watch changes to a given object
     */
    def watch(tp: Topology.Type, id: Commons.UUID): RequestState =
        command(newGet(tp, watch = true, id))

    /**
     * Watch changes to any object of a given type
     */
    def watchAll(tp: Topology.Type): RequestState =
        command(newGet(tp, watch = true, null))

    /**
     * Unsubscribe to changes to a given object
     */
    def unwatch(tp: Topology.Type, id: Commons.UUID): RequestState =
        command(newUnsubs(tp, id))

    /**
     * Unsubscribe to changes to any object of a given type
     */
    def unwatchAll(tp: Topology.Type): RequestState =
        command(newUnsubs(tp, null))

    /**
     * Retrieve the sequence number of the last event received from the server
     */
    def lastEventSeqno: Long = lastSeqno.get()
}

object ClientSession {
    case class RequestState(reqId: Commons.UUID, futureResp: Future[Boolean])

    class ClientException(msg: String) extends Exception(msg)

    class ClientHandshakeRejectedException(msg: String)
        extends ClientException(msg)

    class ClientCommandRejectedException(msg: String)
        extends ClientException(msg)
}


