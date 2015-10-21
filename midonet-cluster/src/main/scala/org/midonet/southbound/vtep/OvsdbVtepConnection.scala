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

package org.midonet.southbound.vtep

import java.net.{ConnectException, InetAddress, SocketException, UnknownHostException}
import java.util.concurrent.Executors.newSingleThreadExecutor
import java.util.concurrent._
import java.util.concurrent.atomic.{AtomicInteger, AtomicReference}

import scala.async.Async
import scala.concurrent.ExecutionContext.fromExecutor
import scala.concurrent.duration.{Duration, _}
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.control.NonFatal

import com.fasterxml.jackson.annotation.JsonInclude.Include
import com.fasterxml.jackson.databind.{DeserializationFeature, ObjectMapper}
import com.lmax.disruptor.util.DaemonThreadFactory
import com.typesafe.scalalogging.Logger
import io.netty.bootstrap.Bootstrap
import io.netty.channel._
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.NioSocketChannel
import io.netty.handler.codec.string.StringEncoder
import io.netty.handler.timeout.IdleStateHandler
import io.netty.util.CharsetUtil
import io.netty.util.concurrent.{Future => NettyFuture, GenericFutureListener}
import org.opendaylight.ovsdb.lib.OvsdbClient
import org.opendaylight.ovsdb.lib.OvsdbConnectionInfo.ConnectionType
import org.opendaylight.ovsdb.lib.impl.OvsdbClientImpl
import org.opendaylight.ovsdb.lib.jsonrpc.{ExceptionHandler, JsonRpcDecoder, JsonRpcEndpoint, JsonRpcServiceBinderHandler}
import org.opendaylight.ovsdb.lib.message.OvsdbRPC
import org.opendaylight.ovsdb.lib.schema.DatabaseSchema
import org.slf4j.LoggerFactory.getLogger
import rx.schedulers.Schedulers.from
import rx.subjects.BehaviorSubject
import rx.{Observable, Subscription}

import org.midonet.cluster.data.vtep.model.VtepEndPoint
import org.midonet.cluster.data.vtep.{VtepNotConnectedException, VtepStateException}
import org.midonet.packets.IPv4Addr
import org.midonet.southbound.vtepLog
import org.midonet.southbound.vtep.ConnectionState.State
import org.midonet.southbound.vtep.OvsdbVtepConnection._
import org.midonet.util.functors.{makeAction1, makeFunc1}

object OvsdbVtepConnection {

    val SuccessfulFuture = Future.successful({})

    // Connection handle
    case class OvsdbHandle(client: OvsdbClient, db: DatabaseSchema)

    // Connection states
    abstract class ConnectionStatus(val state: State) {
        override def toString = state.toString()
    }

    // - Disconnected: The state when:
    //   (a) The client is created.
    //   (b) Connection has been terminated by a explicit user request
    case object Disconnected
        extends ConnectionStatus(ConnectionState.Disconnected)
    // - Connecting: The state during an asynchronous connect operation to the
    //   VTEP. The client is connecting when:
    //   (a) The connect method is called on a disconnected client
    //   (b) The connection is broken, and a reconnection is attempted
    case class Connecting(retries: Long, complete: Future[Unit])
        extends ConnectionStatus(ConnectionState.Connecting)
    // - Connected: The state when:
    //   (a) The connection to the VTEP has been successfully established
    case class Connected(channel: Channel, client: OvsdbClient,
                         complete: Future[Unit])
        extends ConnectionStatus(ConnectionState.Connected)
    // - Ready: The state when:
    //   (a) The connection has been established and the table schema
    //       information has been downloaded from the VTEP.
    case class Ready(channel: Channel, client: OvsdbClient, handle: OvsdbHandle)
        extends ConnectionStatus(ConnectionState.Ready)
    // - Disconnecting: The state when:
    //   (a) A disconnection has ben requested, but the operation has not
    //       yet been completed.
    case class Disconnecting(complete: Future[Unit])
        extends ConnectionStatus(ConnectionState.Disconnecting)
    // - Broken: The state when:
    //   (a) A connection request failed
    //   (b) A connection was dropped from the vtep/network
    case class Broken(retryInterval: Duration, retries: Long,
                      subscription: Subscription)
        extends ConnectionStatus(ConnectionState.Broken)
    // - Failed: The state when:
    //   (a) The connection is broken and no retries are left
    //   (b) Connecting failed for any reason other than a connection or socket
    //       exception
    case object Failed extends ConnectionStatus(ConnectionState.Failed)
    // - Disposed: The state when:
    //   (a) The connection has been closed and all connection resources (e.g.
    //       threads, executors, etc.) have been released. Once a connection is
    //       disposed it cannot be re-open.
    case object Disposed extends ConnectionStatus(ConnectionState.Disposed)
}

/** Provides real connections to an OVSDB instance */
class OvsdbVtepConnectionProvider {
    def get(mgmtIp: IPv4Addr, mgmtPort: Int, retryInterval: Duration = 5 second,
            maxRetries: Int = 0) : VtepConnection = {
        new OvsdbVtepConnection(mgmtIp, mgmtPort, retryInterval, maxRetries)
    }
}

/** This class handles the connection to an OVSDB-compliant VTEP. */
class OvsdbVtepConnection(mgmtIp: IPv4Addr, mgmtPort: Int,
                          retryInterval: Duration, maxRetries: Int)
    extends VtepConnection {

    private type Handler = PartialFunction[ConnectionStatus, Future[Unit]]

    // Do this or it'll interfere with appender config
    private val log = Logger(getLogger(vtepLog(mgmtIp, mgmtPort)))

    // We must have 1 thread per VTEP
    private val executor = newSingleThreadExecutor(DaemonThreadFactory.INSTANCE)
    private val scheduler = from(executor)
    private implicit val executionContext = fromExecutor(executor)

    private val eventLoopId = new AtomicInteger
    private val eventLoopGroup = new NioEventLoopGroup(0, new ThreadFactory {
        override def newThread(r: Runnable): Thread = {
            new Thread(r, s"vtep-$endPoint-nio-" +
                          s"${eventLoopId.getAndIncrement()}")
        }
    })

    private val state = new AtomicReference[ConnectionStatus](Disconnected)
    private val stateSubject = BehaviorSubject.create[ConnectionStatus](state.get)
    private val stateObservable = stateSubject.asObservable
                                              .map[State](makeFunc1(_.state))

    protected class ChannelCloseListener(val channel: Channel,
                                       val client: OvsdbClient)
        extends ChannelFutureListener {
        @throws[Exception]
        override def operationComplete(future: ChannelFuture): Unit = {
            handle(onBroken(channel, client))
        }
    }

    override val endPoint = VtepEndPoint(mgmtIp, mgmtPort)

    /** Connects to the VTEP. */
    override def connect(): Future[State] = {
        handle(onConnect) map { _ => state.get.state }
    }

    /** Disconnects from the VTEP. */
    override def disconnect(): Future[State] = {
        handle(onDisconnect) map { _ => state.get.state }
    }

    /** Releases the resources used by the VTEP connection. If the VTEP is
      * connected, it first disconnects from the VTEP. The method receives
      * as argument an execution context, on which the cleanup is performed.
      */
    override def close()(implicit ex: ExecutionContext): Future[State] = {
        handle(onDisconnect) recover { case NonFatal(t) =>
            log.warn("Failed to disconnect when closing the connection", t)
        } flatMap { case _ =>
            updateStatus(Disposed)
            val promise = Promise[State]()
            val listener = new GenericFutureListener[NettyFuture[Any]] {
                override def operationComplete(future: NettyFuture[Any]): Unit = {
                    promise.success(state.get.state)
                }
            }
            eventLoopGroup.shutdownGracefully()
                          .asInstanceOf[NettyFuture[Any]]
                          .addListener(listener)
            promise.future
        }
    }

    /** publish an observable with connection state updates. */
    override def observable = stateObservable

    /** Get the current connection state. */
    override def getState: State = state.get().state

    /** Get the current ovsdb client handler. */
    override def getHandle: Option[OvsdbHandle] = state.get() match {
        case Ready(_,_, handle) => Some(handle)
        case _ => None
    }

    /** Updates the current local state and emits a notification on the state
      * observable.
      */
    private def updateStatus(status: ConnectionStatus): Unit = {
        log.debug("Connection status changed: {}", status.state)
        state set status
        stateSubject onNext status
    }

    /** Connection state machine transition that handles the call to
      * `onConnect`. The partial function returns a future which:
      * - If the connection state is [[Disconnected]], [[Failed]] or [[Broken]]
      *   transitions the connection state to [[Connecting]] and then calls the
      *   `onConnecting` handler.
      * - If the connection state is [[Disconnecting]] it fails immediately.
      * - If the connection state is [[Disposed]] it fails immediately.
      * - If the connection state is [[Connecting]], [[Connected]] or [[Ready]]
      *   it succeeds immediately.
      */
    private val onConnect: Handler = {
        case Disconnected | Failed =>
            log.info("Connect ({}): connecting to VTEP", state.get)
            val complete = handle(onConnecting)
            updateStatus(Connecting(maxRetries, complete))
            complete
        case Broken(interval, retries, subscription) =>
            log.info("Connect ({}): re-connecting to VTEP retry {} of {}",
                     state.get, Long.box(maxRetries - retries + 1),
                     Long.box(maxRetries))
            subscription.unsubscribe()
            val complete = handle(onConnecting)
            updateStatus(Connecting(retries - 1, complete))
            complete
        case Disconnecting(_) =>
            log.warn("Connect ({}): connecting to VTEP failed because it is " +
                     "disconnecting", state.get)
            Future.failed(new VtepStateException(endPoint,
                                                 "VTEP is disconnecting"))
        case Disposed =>
            Future.failed(new VtepStateException(endPoint,
                                                 "Connection is disposed"))
        case other =>
            log.debug("Connect ({}): already connecting/connected", state.get)
            SuccessfulFuture
    }

    /** Connection state machine transition that handles the call to
      * `onConnecting`. The partial function returns a future which:
      * - If the connection state is [[Connecting]] it asynchronously opens
      *   a channel, initializes the RPC client, transitions the state to
      *   [[Connected]] if successful and calls the `onConnected` handler.
      * - If the connection state is [[Connected]] or [[Ready]] it succeeds
      *   immediately.
      * - It fails immediately if the connection state is any other.
      */
    private val onConnecting: Handler = {
        case Connecting(retries, _) =>
            openChannel() map { initializeClient } flatMap { pair =>
                state.get match {
                    case Connecting(_, _) =>
                        val complete = handle(onConnected)
                        val status = Connected(pair._1, pair._2, complete)
                        log.info("Connecting ({}): succeeded", status)
                        updateStatus(status)
                        complete
                    case Disconnecting(_) =>
                        log.info("Connecting ({}): connection canceled",
                                 state.get)
                        closeChannel(pair._1, pair._2) flatMap { _ =>
                            Future.failed(new VtepStateException(
                                endPoint, "Connection canceled"))
                        }
                    case _ =>
                        log.warn("Connection ({}): connection failed",
                                 state.get)
                        closeChannel(pair._1, pair._2) flatMap { _ =>
                            Future.failed(new VtepStateException(
                                endPoint, "Connection state changed"))
                        }
                }
            } recoverWith {
                case e @ (_: ConnectException |
                          _: SocketException |
                          _: UnknownHostException |
                          _: VtepNotConnectedException) =>
                    if (retries > 0 && retryInterval.length > 0) {
                        log.warn("Connecting ({}): failed {} retries left " +
                                 "in {})", state.get, Long.box(retries),
                                 retryInterval, e)
                        val subscription = Observable
                            .timer(retryInterval.toMillis, TimeUnit.MILLISECONDS,
                                   scheduler)
                            .subscribe(makeAction1((_: java.lang.Long) => {
                                handle(onConnect)
                            }))
                        updateStatus(Broken(retryInterval, retries, subscription))
                    } else {
                        log.warn("Connecting ({}): failed", state.get, e)
                        updateStatus(Failed)
                    }
                    Future.failed(new VtepNotConnectedException(endPoint))
                case NonFatal(e) =>
                    log.error("Connecting ({}): unexpected error", state.get, e)
                    updateStatus(Failed)
                    Future.failed(new VtepNotConnectedException(endPoint))
            }
        case Connected(_,_, complete) =>
            log.debug("Connecting ({}): already connecting", state.get)
            complete
        case Ready(_,_,_) =>
            log.debug("Connecting ({}): already connected", state.get)
            SuccessfulFuture
        case other =>
            log.warn("Connecting ({}): VTEP not connecting", state.get)
            Future.failed(new VtepNotConnectedException(endPoint))
    }

    /** Connection state machine transition that handles the call to
      * `onConnected`. The partial function returns a future which:
      * - If the connection state is [[Connected]] it retrieves the OVSDB VTEP
      *   schema and transitions the state to [[Ready]].
      * - It fails immediately if the connection state is any other.
      */
    private val onConnected: Handler = {
        case Connected(channel, client, _) =>
            log.info("Connected ({}): retrieving VTEP schema", state.get)
            OvsdbOperations.getDbSchema(
                client, OvsdbOperations.DbHardwareVtep, channel.closeFuture(),
                new VtepNotConnectedException(endPoint))(executor) flatMap { schema =>

                log.info("Connected ({}): retrieved hardware VTEP schema",
                         state.get)
                state.get match {
                    case Connected(ch, c, _) if c == client =>
                        updateStatus(Ready(channel, client,
                                           OvsdbHandle(client, schema)))
                        SuccessfulFuture
                    case Disconnecting(_) =>
                        log.info("Connected ({}): connection canceled",
                                 state.get)
                        closeChannel(channel, client) flatMap { _ =>
                            Future.failed(new VtepStateException(
                                endPoint, "Connection canceled"))
                        }
                    case Disconnected | Broken(_,_,_) | Failed =>
                        log.warn("Connected ({}): connection failed", state.get)
                        Future.failed(new VtepNotConnectedException(endPoint))
                    case _ =>
                        log.debug("Connected ({}): ignore VTEP schema because " +
                                  "connection state changed", state.get)
                        SuccessfulFuture
                }
            }
        case other =>
            log.warn("Connecting ({}): VTEP not connected", state.get)
            Future.failed(new VtepNotConnectedException(endPoint))
    }

    /** Connection state machine transition that handles the call to
      * `onDisconnect`. The partial function returns a future which:
      * - If the connection state is [[Disconnected]], succeeds immediately.
      * - If the connection state is [[Disconnecting]], it returns the future
      *   of the current disconnecting state.
      * - If the connection state is [[Disposed]], fail immediately.
      * - If the connection state is [[Failed]], it changes the state to
      *   [[Disconnected]] and succeeds immediately.
      * - If the connection state is [[Broken]], it cancels the re-connect
      *   subscription, changes the state to [[Disconnected]] and succeeds
      *   immediately.
      * - If the connection state is [[Connecting]], it enqueues to the current
      *   connecting future a new `onDisconnect` transition and it returns that
      *   future.
      * - If the connection state is [[Connected]], it enqueues to the current
      *   connected future a new `onDisconnect` transition and it returns that
      *   future.
      * - If the connection state is [[Ready]], it closes the NETTY channel
      *   and changes the state to [[Disconnecting]].
      */
    private val onDisconnect: Handler = {
        case Disconnected =>
            log.debug("Disconnect ({}): VTEP already disconnected", state.get)
            SuccessfulFuture
        case Disconnecting(complete) =>
            log.debug("Disconnect ({}): VTEP already disconnecting", state.get)
            complete
        case Disposed =>
            log.error("Disconnect ({}): VTEP connection is disposed", state.get)
            Future.failed(new VtepStateException(endPoint,
                                                 "Connection is disposed"))
        case Failed =>
            log.info("Disconnect ({}): VTEP disconnected", state.get)
            updateStatus(Disconnected)
            SuccessfulFuture
        case Broken(_,_, subscription) =>
            log.info("Disconnect ({}): VTEP disconnected", state.get)
            subscription.unsubscribe()
            updateStatus(Disconnected)
            SuccessfulFuture
        case Connecting(_, complete) =>
            log.info("Disconnect ({}): VTEP connection canceled", state.get)
            complete recover { case _ => } flatMap { _ => handle(onDisconnect) }
        case Connected(_,_, complete) =>
            log.info("Disconnect ({}): VTEP connection canceled", state.get)
            complete flatMap { _ => handle(onDisconnect) }
        case Ready(channel, client, _) =>
            log.info("Disconnect ({}): VTEP disconnecting", state.get)
            val complete = closeChannel(channel, client) flatMap { _ =>
                handle(onDisconnected)
            }
            updateStatus(Disconnecting(complete))
            complete
    }

    /** Connection state machine transition that handles the completion of
      * the [[Disconnecting]] state. The partial function returns a future,
      * which:
      * - If the connection state is [[Disconnecting]], it changes the state
      *   to [[Disconnected]] and succeeds immediately.
      * - Otherwise, it fails immediately.
      */
    private val onDisconnected: Handler = {
        case Disconnecting(_) =>
            log.info("Disconnected ({}): VTEP disconnected", Disconnected)
            updateStatus(Disconnected)
            SuccessfulFuture
        case _ =>
            log.warn("Disconnected ({}): VTEP disconnecting failed", state.get)
            Future.failed(new VtepStateException(endPoint,
                                                 "Failed to disconnect"))
    }

    /** Connection state machine transition that handles a broken connection.
      * The partial function only applies to a connection in the [[Ready]]
      * state, when the connection state is changed to [[Disconnected]] and
      * immediately attempts to reconnect.
      */
    private def onBroken(channel: Channel, client: OvsdbClient): Handler = {
        case Ready(ch, cl, _) if channel == ch && client == cl =>
            log.warn("Broken ({}): VTEP connection lost", state.get)
            updateStatus(Disconnected)
            handle(onConnect)
        case _ => // For all other states, the broken connection will be
                  // handled by the respective state.
            SuccessfulFuture
    }

    /** Handles the specified VTEP connection state machine transition. The
      * method receives as argument a [[Handler]], which is a partial function
      * to which the current connection state is applied.
      */
    private def handle(handler: Handler): Future[Unit] = Async.async {
        Async.await(handler(state.get))
    }

    /** Opens a NETTY channel to the current VTEP end-point. The method is
      * asynchronous and returns a future, which when successful completes with
      * the current channel instance.
      */
    protected def openChannel(): Future[Channel] = {
        val address = InetAddress.getByName(endPoint.mgmtIp.toString)

        val bootstrap = new Bootstrap()
        bootstrap.group(eventLoopGroup)
        bootstrap.channel(classOf[NioSocketChannel])
        bootstrap.option(ChannelOption.TCP_NODELAY, Boolean.box(true))
        bootstrap.option(ChannelOption.RCVBUF_ALLOCATOR,
                         new AdaptiveRecvByteBufAllocator(65535, 65535,
                                                          65535))
        bootstrap.handler(new ChannelInitializer[SocketChannel]() {
            @throws(classOf[Exception])
            def initChannel(channel: SocketChannel) {
                channel.pipeline.addLast(new JsonRpcDecoder(100000),
                                         new StringEncoder(CharsetUtil.UTF_8),
                                         new ExceptionHandler)
            }
        })

        val promise = Promise[Channel]()
        bootstrap.connect(address, endPoint.mgmtPort)
                 .addListener(new ChannelFutureListener {
            override def operationComplete(future: ChannelFuture): Unit = {
                if (future.isSuccess) promise success future.channel()
                else promise failure future.cause()
            }
        })

        promise.future
    }

    /** Initializes the JSON RPC client for the specified NETTY channel. The
      * method is synchronous and returns the current NETTY channel and RPC
      * client.
      */
    protected def initializeClient(channel: Channel): (Channel, OvsdbClient) = {
        val objectMapper = new ObjectMapper
        objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
                               false)
        objectMapper.setSerializationInclusion(Include.NON_NULL)

        val factory = new JsonRpcEndpoint(objectMapper, channel)
        val binderHandler = new JsonRpcServiceBinderHandler(factory)
        binderHandler.setContext(channel)
        val idleHandler = new IdleStateHandler(30, 30, 0)
        channel.pipeline.addLast(idleHandler)
        channel.pipeline.addLast(binderHandler)

        val rpc = factory.getClient(channel, classOf[OvsdbRPC])
        val client = new OvsdbClientImpl(rpc, channel, ConnectionType.ACTIVE,
                                         executor)
        channel.closeFuture.addListener(newCloseListener(channel, client))
        (channel, client)
    }

    /** Closes the NETTY channel and returns a future which completes when the
      * connection has been closed.
      */
    protected def closeChannel(channel: Channel, client: OvsdbClient)
    : Future[Channel] = {
        val promise = Promise[Channel]()
        channel.close().addListener(new ChannelFutureListener {
            override def operationComplete(future: ChannelFuture): Unit = {
                if (future.isSuccess) promise success future.channel()
                else promise failure future.cause()
            }
        })
        promise.future
    }

    /** Creates a new channel close listener for this OVSDB VTEP connection. */
    protected def newCloseListener(channel: Channel, client: OvsdbClient)
    : ChannelCloseListener = {
        new ChannelCloseListener(channel, client)
    }

}

