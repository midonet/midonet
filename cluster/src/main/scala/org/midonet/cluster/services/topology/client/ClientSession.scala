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

package org.midonet.cluster.services.topology.client

import java.util.UUID
import java.util.concurrent.{TimeUnit, Executors}
import java.util.concurrent.atomic.{AtomicReference, AtomicLong}

import io.netty.channel.{ChannelFuture, ChannelHandlerContext}
import io.netty.util.concurrent.GenericFutureListener
import org.midonet.cluster.models.{Commons, Topology}
import org.midonet.cluster.util.UUIDUtil
import org.slf4j.LoggerFactory
import rx.subjects.{PublishSubject, Subject}
import rx.{Observable, Observer}

import org.midonet.cluster.rpc.Commands
import org.midonet.cluster.services.topology.common._
import org.midonet.util.functors.makeAction0
import org.midonet.util.netty.{ClientFrontEnd, ProtoBufWebSocketAdapter, ProtoBufSocketAdapter}

import scala.collection.concurrent.TrieMap
import scala.concurrent.duration.Duration
import scala.concurrent.{Await, ExecutionContext, Future, Promise}
import scala.util.{Try, Failure, Success}

/**
 * A client session for the Topology service
 * @param host is the service host
 * @param port is the service port
 * @param ws is the websocket url path, or null for a non-ws connection
 */
class ClientSession(val host: String, port: Int, ws: String)
    extends Observer[CommEvent] {

    def this(host: String, port: Int) = this(host, port, null)

    private implicit val ec = ExecutionContext.fromExecutorService(
        Executors.newSingleThreadExecutor())

    private val log = LoggerFactory.getLogger(classOf[ClientSession])
    private val sessionId = UUID.randomUUID()
    private val handler = new ApiClientHandler(this)
    private val adapter = if (ws == null || ws.isEmpty)
        new ProtoBufSocketAdapter(handler, Commands.Response.getDefaultInstance)
    else
        new ProtoBufWebSocketAdapter(handler,
                                     Commands.Response.getDefaultInstance, ws)
    private val srv = new ClientFrontEnd(adapter, host, port)

    object State extends Enumeration {
        type State = Value
        val READY, CONNECTING, HANDSHAKE, CONNECTED, BROKEN,
            TERMINATED, TERMINATING, DISPOSED = Value
    }

    private val state = new AtomicReference[State.Value](State.READY)
    private val connection = Promise[ChannelHandlerContext]()

    private val lastSeqno = new AtomicLong(0)

    // handshake completion
    private val connected = Promise[Boolean]()
    connected.future.onComplete({
        case Success(true) =>
            log.info("session handshake successful: " + sessionId)
            state.compareAndSet(State.HANDSHAKE, State.CONNECTED)
        case Success(false) =>
            log.info("session handshake failed: " + sessionId)
            state.compareAndSet(State.HANDSHAKE, State.BROKEN)
            deactivate()
        case Failure(exc) =>
            state.compareAndSet(State.HANDSHAKE, State.BROKEN)
            log.warn("session handshake aborted: " + sessionId, exc)
            deactivate()
    })

    // complete any pending requests on close
    private val closed = Promise[Boolean]()
    closed.future.onComplete({case cause: Try[Boolean] =>
        log.info("connection closed: " + sessionId)
        pending.foreach(r => {pending.remove(r._1).map({_.tryComplete(cause)})})
        cause match {
            case Failure(error) => updateStream.onError(error)
            case Success(_) => updateStream.onCompleted()
        }
    })

    private val pending = new TrieMap[Commons.UUID, Promise[Boolean]]()

    private val updateStream: Subject[Commands.Response, Commands.Response] =
        PublishSubject.create()
    private val updateOutput = updateStream.asObservable().doOnSubscribe(
        makeAction0
        {
            try {
                srv.startAsync().awaitRunning()
            } catch {
                case e: Throwable =>
                    log.error("connection failed for session: " + sessionId, e)
                    closed.tryFailure(e)
                    if (state.compareAndSet(State.CONNECTED, State.BROKEN) ||
                        state.compareAndSet(State.TERMINATING, State.BROKEN)) {
                        deactivate()
                    }
            }
        }
    )

    override protected def onNext(ev: CommEvent): Unit = ev match {
        case Connect(ctx) =>
            log.info("connection established for session: " + sessionId)
            if (state.compareAndSet(State.CONNECTING, State.HANDSHAKE)) {
                connection.trySuccess(ctx)
                val (reqId, hs) = newHandshake()
                pending.put(reqId, connected)
                // this goes before any queued send
                ctx.writeAndFlush(hs)
            }
        case Disconnect(ctx) =>
            log.info("connection terminated for session: " + sessionId)
            closed.trySuccess(false)
            if (state.compareAndSet(State.CONNECTED, State.TERMINATED) ||
                state.compareAndSet(State.TERMINATING, State.TERMINATED)) {
                deactivate()
            }
        case Error(ctx, exc) =>
            log.error("connection aborted for session: " + sessionId)
            closed.tryFailure(exc)
            if (state.compareAndSet(State.CONNECTED, State.BROKEN) ||
                state.compareAndSet(State.TERMINATING, State.BROKEN)) {
                deactivate()
            }
        case Response(ctx, proto) =>
            if (proto.hasSeqno) lastSeqno.set(proto.getSeqno)
            proto match {
                case p: Commands.Response if p.hasAck || p.hasNack =>
                    val id =
                        if (p.hasAck) p.getAck.getReqId else p.getNack.getReqId
                    pending.remove(id).map{_.trySuccess(p.hasAck)}
                case p: Commands.Response =>
                    updateStream.onNext(p)
            }
    }
    override protected def onCompleted(): Unit = {
        log.error("session terminated unexpectedly: " + sessionId)
    }
    override protected def onError(exc: Throwable): Unit = {
        log.error("session connection broken: " + sessionId, exc)
    }

    private def deactivate() = {
        if (state.getAndSet(State.DISPOSED) != State.DISPOSED) {
            if (srv.isRunning)
                srv.stopAsync().awaitTerminated()
            ec.shutdown()
            try {
                if (!ec.awaitTermination(5, TimeUnit.SECONDS)) {
                    ec.shutdownNow()
                    if (!ec.awaitTermination(5, TimeUnit.SECONDS)) {
                        log.error("unable to shutdown client session thread: " +
                                  sessionId)
                    }
                }
            } catch {
                case e: InterruptedException =>
                    log.warn(
                        "interrupted while waiting for session completion: " +
                        sessionId)
                    ec.shutdownNow()
                    Thread.currentThread().interrupt()
            }
        }
    }

    private val lastMsg = new AtomicReference[Future[Boolean]](null)
    private def send(msg: Commands.Request) = {
        val completed = Promise[Boolean]()
        val future = lastMsg.getAndSet(completed.future)
        if (future == null)
            connection.future.onComplete({
                case Success(ctx) =>
                    ctx.writeAndFlush(msg).addListener(
                        new GenericFutureListener[ChannelFuture] {
                            override def operationComplete(f: ChannelFuture) = {
                                if (f.isSuccess) completed.success(true)
                                else if (f.isCancelled) completed.success(false)
                                else completed.failure(f.cause)
                            }
                        }
                    )
                case Failure(err) => completed.failure(err)
            })
        else
            future.onComplete({
                case Success(true) =>
                    val ctx = connection.future.value.get.get
                    ctx.writeAndFlush(msg).addListener(
                        new GenericFutureListener[ChannelFuture] {
                            override def operationComplete(f: ChannelFuture) = {
                                if (f.isSuccess) completed.success(true)
                                else if (f.isCancelled) completed.success(false)
                                else completed.failure(f.cause)
                            }
                        }
                    )
                case Success(false) => completed.success(false)
                case Failure(err) => completed.failure(err)

            })
    }

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

    private def newGet(tp: Topology.Type, watch: Boolean, oid: Commands.ID):
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

    private def newUnsubs(tp: Topology.Type, oid: Commands.ID):
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

    /**
     * Get an update stream that will start emitting data on first subscribe
     */
    def connect(): Observable[Commands.Response] = updateOutput

    /**
     * Terminate the session and disconnect from server
     */
    def terminate(): Future[Boolean] = {
        if (state.compareAndSet(State.READY, State.DISPOSED)) {
            connected.success(false)
            closed.success(true)
        } else if (state.compareAndSet(State.CONNECTING, State.TERMINATING) ||
                   state.compareAndSet(State.HANDSHAKE, State.TERMINATING) ||
                   state.compareAndSet(State.CONNECTED, State.TERMINATING)) {
            val p = Promise[Boolean]()
            val (reqId, bye) = newBye()
            pending.put(reqId, p)
            send(bye)
        }
        closed.future
    }

    /**
     * Wait for connection termination
     */
    def awaitTermination(atMost: Duration): Option[Throwable] = {
        log.info("SOCKET STATE ENTERING: " + closed.future.value)
        val cause = Await.ready(closed.future, atMost).value.get match {
            case Failure(cause) => Some(cause)
            case Success(_) => None
        }
        log.info("SOCKET STATE EXITING: " + closed.future.value)
        cause
    }

    /**
     * Retrieve the last state of a specific object
     */
    def get(tp: Topology.Type, id: Commands.ID) = {
        val (_, req) = newGet(tp, watch = false, id)
        send(req)
    }

    /**
     * Watch changes to a given object
     */
    def watch(tp: Topology.Type, id: Commands.ID) = {
        val (_, req) = newGet(tp, watch = true, id)
        send(req)
    }

    /**
     * Watch changes to any object of a given type
     */
    def watchAll(tp: Topology.Type): Future[Boolean] = {
        val p = Promise[Boolean]()
        val (reqId, req) = newGet(tp, watch = true, null)
        pending.put(reqId, p)
        send(req)
        p.future
    }

    /**
     * Unsubscribe to changes to a given object
     */
    def unwatch(tp: Topology.Type, id: Commands.ID): Future[Boolean] = {
        val p = Promise[Boolean]()
        val (reqId, req) = newUnsubs(tp, id)
        pending.put(reqId, p)
        send(req)
        p.future
    }

    /**
     * Unsubscribe to changes to any object of a given type
     */
    def unwatchAll(tp: Topology.Type): Future[Boolean] = {
        val p = Promise[Boolean]()
        val (reqId, req) = newUnsubs(tp, null)
        pending.put(reqId, p)
        send(req)
        p.future
    }


}
