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
import java.util.concurrent.Executor
import java.util.concurrent.atomic.{AtomicInteger, AtomicReference, AtomicLong}

import com.google.common.util.concurrent.Service
import com.google.common.util.concurrent.Service.Listener
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
 * @param wspath is the websocket url path, or null for a non-ws connection
 * @param sessionId is the id for this session (may be forced to a certain
 *                  value to recover a disconnected session.
 * @param startAt is the first event that should be retrieved from a lost
 *                session (usually, the last even before disconnection)
 */
class ClientSession(val host: String, val port: Int, val wspath: String,
                    val sessionId: UUID = UUID.randomUUID(),
                    val startAt: Long = 0)
    extends Observer[CommEvent] {

    def this(host: String, port: Int) =
        this(host, port, null, UUID.randomUUID(), 0)
    def this(host: String, port: Int, sessionId: UUID) =
        this(host, port, null, sessionId, 0)
    def this(host: String, port: Int, sessionId: UUID, startAt: Long) =
        this(host, port, null, sessionId, startAt)

    import ClientSession._

    // Execute continuations on the completing thread
    private implicit val atSameThread = new Executor {
        override def execute(runnable: Runnable): Unit = runnable.run()
    }
    private implicit val ec = ExecutionContext.fromExecutor(atSameThread)

    private val log = LoggerFactory.getLogger(classOf[ClientSession])
    private val lastSeqno = new AtomicLong(0)

    private val handler = new ApiClientHandler(this)
    private val adapter = if (wspath == null || wspath.isEmpty)
        new ProtoBufSocketAdapter(handler, Commands.Response.getDefaultInstance)
    else
        new ProtoBufWebSocketAdapter(handler,
                                     Commands.Response.getDefaultInstance,
                                     wspath)
    private val srv = new ClientFrontEnd(adapter, host, port)

    // Handle abstract service
    private val stopped = Promise[Boolean]()
    srv.addListener(new Listener {
        override def terminated(from: Service.State) = stopped.trySuccess(true)
        override def failed(from: Service.State, err: Throwable) = from match {
            case Service.State.NEW =>
                stopped.tryFailure(err)
                closed.tryFailure(err)
            case Service.State.STARTING =>
                stopped.tryFailure(err)
                closed.tryFailure(err)
            case _ =>
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
    private def stopService() = Try(srv.stopAsync().awaitTerminated())

    // Send a request to the server
    private def send(msg: Commands.Request): Future[Boolean] = {
        val sent = Promise[Boolean]()
        connected.future.value match {
            case Some(Success(ctx)) if !closed.future.isCompleted =>
                ctx.writeAndFlush(msg).addListener(
                    new GenericFutureListener[ChannelFuture] {
                        override def operationComplete(f: ChannelFuture) = {
                            if (f.isSuccess) sent.success(true)
                            else if (f.isCancelled) sent.success(false)
                            else sent.failure(f.cause)
                        }
                    }
                )
            case Some(Failure(err)) => sent.failure(err)
            case _ => sent.failure(
                new IllegalStateException("no communication context available"))
        }
        sent.future
    }

    // Send a client command (with backpressure)
    private val lastCommand = new AtomicReference[Future[Boolean]](null)
    private def sendCommand(req: Commands.Request): Future[Boolean] = {
        val done = Promise[Boolean]()
        var previous = lastCommand.getAndSet(done.future)
        if (previous == null)
            previous = handshake.future
        previous.onSuccess({case _ => done.completeWith(send(req))})
        previous.onFailure({case exc => done.failure(exc)})
        done.future
    }

    // Process response
    private def parse(msg: Commands.Response): Option[(Commons.UUID, Boolean)] =
        msg match {
            case m: Commands.Response if m.hasAck =>
                Some((m.getAck.getReqId, true))
            case m: Commands.Response if m.hasNack =>
                Some((m.getNack.getReqId, false))
            case _ => None
        }

    // Handle protocol
    private val handshakeId = new AtomicReference[Commons.UUID](null)
    private val connected = Promise[ChannelHandlerContext]()
    private val handshake = Promise[Boolean]()
    private val closed = Promise[Boolean]()

    connected.future.onSuccess({case ctx => try {
        log.info("connection established: " + sessionId)
        val (id, req) = newHandshake()
        handshakeId.set(id)
        send(req)
    } catch {
        case exc: Throwable => handshake.tryFailure(exc)

    }})

    handshake.future.onSuccess({case _ =>
        log.info("handshake successful: " + sessionId)
    })
    handshake.future.onFailure({case exc =>
        log.warn("handshake failed: " + sessionId, exc)
    })

    closed.future.onComplete(exit => {
        exit match {
            case Success(_) => updateStream.onCompleted()
            case Failure(exc) => updateStream.onError(exc)
        }
        pending.foreach {req => pending.remove(req._1).map{_.tryComplete(exit)}}
    })

    override protected def onNext(ev: CommEvent): Unit = ev match {
        case Connect(ctx) =>
            connected.success(ctx)
        case Disconnect(ctx) =>
            closed.trySuccess(true)
        case Error(ctx, exc) =>
            closed.tryFailure(exc)
        case Response(ctx, proto) if !handshake.isCompleted =>
            if (proto.hasSeqno) lastSeqno.set(proto.getSeqno)
            parse(proto) match {
                case Some((id, true)) if id.equals(handshakeId.get) =>
                    handshake.trySuccess(true)
                case Some((id, false)) if id.equals(handshakeId.get) =>
                    handshake.tryFailure(
                        new ClientHandshakeRejectedException(
                            "handshake rejected: " + sessionId +
                            " at " + startAt))
                case _ =>
            }
        case Response(ctx, proto) => parse(proto) match {
            case Some((id, true)) =>
                pending.remove(id).map{_.trySuccess(true)}
            case Some((id, false)) =>
                pending.remove(id).map{_.tryFailure(
                    new ClientCommandRejectedException("command rejected: " +
                                                       UUIDUtil.fromProto(id)))}
            case _ => updateStream.onNext(proto)
        }
    }
    override protected def onCompleted(): Unit = {
        log.error("session terminated unexpectedly: " + sessionId)
        closed.trySuccess(false)
    }
    override protected def onError(exc: Throwable): Unit = {
        log.error("session connection broken: " + sessionId, exc)
        closed.tryFailure(exc)
    }

    private val updateStream: Subject[Commands.Response, Commands.Response] =
        PublishSubject.create()
    private val updateOutput = updateStream.asObservable()
        .doOnTerminate(makeAction0 {stopService()})
        .doOnSubscribe(makeAction0 {startService()})

    private val pending = new TrieMap[Commons.UUID, Promise[Boolean]]()

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
        if (service.incrementAndGet() == 1) {
            closed.success(true)
            stopped.success(true)
        } else {
            val (reqId, bye) = newBye()
            val futureResp = Promise[Boolean]()
            pending.put(reqId, futureResp)
            sendCommand(bye)
        }
        closed.future
    }

    def terminateNow(): Future[Boolean] = {
        if (service.incrementAndGet() == 1) {
            closed.success(true)
            stopped.success(true)
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

    /**
     * Retrieve the last state of a specific object
     */
    def get(tp: Topology.Type, id: Commands.ID) = {
        val (_, req) = newGet(tp, watch = false, id)
        sendCommand(req)
    }

    /**
     * Watch changes to a given object
     */
    def watch(tp: Topology.Type, id: Commands.ID) = {
        val (_, req) = newGet(tp, watch = true, id)
        sendCommand(req)
    }

    /**
     * Watch changes to any object of a given type
     */
    def watchAll(tp: Topology.Type): Future[Boolean] = {
        val futureResp = Promise[Boolean]()
        val (reqId, req) = newGet(tp, watch = true, null)
        pending.put(reqId, futureResp)
        sendCommand(req)
        futureResp.future
    }

    /**
     * Unsubscribe to changes to a given object
     */
    def unwatch(tp: Topology.Type, id: Commands.ID): Future[Boolean] = {
        val futureResp = Promise[Boolean]()
        val (reqId, req) = newUnsubs(tp, id)
        pending.put(reqId, futureResp)
        sendCommand(req)
        futureResp.future
    }

    /**
     * Unsubscribe to changes to any object of a given type
     */
    def unwatchAll(tp: Topology.Type): Future[Boolean] = {
        val futureResp = Promise[Boolean]()
        val (reqId, req) = newUnsubs(tp, null)
        pending.put(reqId, futureResp)
        sendCommand(req)
        futureResp.future
    }

    /**
     * Retrieve the sequence number of the last event received from the server
     */
    def lastEventSeqno: Long = lastSeqno.get()
}

object ClientSession {
    class ClientException(msg: String) extends Exception(msg)
    class ClientHandshakeRejectedException(msg: String)
        extends ClientException(msg)
    class ClientCommandRejectedException(msg: String)
        extends ClientException(msg)
}


