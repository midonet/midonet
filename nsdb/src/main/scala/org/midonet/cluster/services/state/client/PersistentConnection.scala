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

package org.midonet.cluster.services.state.client

import java.nio.channels.ClosedChannelException
import java.util.concurrent.atomic.{AtomicInteger, AtomicReference}
import java.util.concurrent.{CancellationException, ScheduledThreadPoolExecutor}

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future, Promise}

import com.google.protobuf.Message
import com.typesafe.scalalogging.Logger

import org.slf4j.LoggerFactory

import rx.Observer

import org.midonet.cluster.rpc.State.ProxyResponse

import io.netty.channel.nio.NioEventLoopGroup


/**
  * The PersistentConnection abstract class will maintain a persistent
  * connection to a remote server by means of the [[Connection]] class.
  * When the connection is dropped (due to a close by the remote server,
  * or an error) it will try to reconnect indefinitely.
  *
  * @param host the remote server host
  * @param port the remote port to connect to
  * @param executor ScheduledThreadPoolExecutor used to schedule reconnects
  * @param retryTimeout the time to wait between reconnect attempts.
  *                     The default is 3 seconds.
  * @param context the executionContext
  * @param eventLoopGroup is the [[NioEventLoopGroup]] to be used for Netty's
  *                       event loop.
  * Usage
  *
  * Derive your class from PersistentConnection.
  * Implement the missing Observer[Message] method: onNext(msg)
  * More advanced behavior can be attained by overriding [[onConnect()]] and
  * [[onDisconnect()]]
  *
  * Call [[start()]] when you want the Connection to be initiated.
  * Call [[stop()]] to close the current connection and disable the reconnection
  * mechanism. Once stopped, it can be started again if needed.
  */
abstract class PersistentConnection(val host: String,
                                    val port: Int,
                                    executor: ScheduledThreadPoolExecutor,
                                    retryTimeout: FiniteDuration = PersistentConnection
                                        .DefaultRetryDelay)
                                   (implicit val context: ExecutionContext,
                                    val eventLoopGroup: NioEventLoopGroup)
    extends Observer[Message] {

    import PersistentConnection._

    private val log = Logger(LoggerFactory.getLogger(classOf[PersistentConnection]))

    private val state = new AtomicReference(Disconnected : State)
    private val counter = new AtomicInteger(0)

    /**
      * Override this method to have a custom source for Connection instances.
      * Useful for mocks.
      */
    protected def connectionFactory(parent: PersistentConnection): Connection =
                        new Connection(parent.host,
                                       parent.port,
                                       parent,
                                       ProxyResponse.getDefaultInstance)

    /**
      * Override this method to implement custom behavior when the connection
      * is established (for the first time or after a connection retry)
      */
    protected def onConnect(): Unit = {}

    /**
      * Override this method to implement custom behavior when the connection
      * is closed (due to stop(), local or remote close(), or an error)
      */
    protected def onDisconnect(cause: Throwable): Unit = {}

    final protected def onCompleted(): Unit =
        handleDisconnect(new ClosedChannelException())

    final protected def onError(cause: Throwable): Unit =
        handleDisconnect(cause)

    private def handleDisconnect(cause: Throwable) = {
        log.warn(s"Disconnected: $cause")
        val current = state.get
        current match {
            case Connected(_) =>
            case _ => log.warn(s"Disconnect in unexpected state $current")
        }
        if (state.compareAndSet(current,Connecting(counter.incrementAndGet()))) {
            onDisconnect(cause)
            delayedStart()
        }
        else {
            // user override by close() or start().
        }
    }

    private def handleConnect(attemptId: Int, connection: Connection): Unit = {
        try {
            val current = state.get
            current match {
                case Connecting(id) if id == attemptId =>
                    if (state.compareAndSet(current, Connected(connection))) {
                        onConnect()
                    } else {
                        throw connectionCancelledException
                    }
                case Connected(_) => throw alreadyConnectedException
                case Disconnected => throw connectionCancelledException
                case _ => throw connectionInProgressException
            }
        } catch {
            // close this connection if cancelled
            case err: Throwable => connection.close() ; throw err
        }
    }

    private def connect(currentState: State): Future[Unit] = {
        val attempt = counter.incrementAndGet()
        val guardState = Connecting(attempt)
        if (state.compareAndSet(currentState, guardState)) {
            // now we are the owners of this connecting state
            // no two connection attempts can be alive now, but we
            // can still be cancelled by stop()
            val connection = connectionFactory(this)
            connection.connect()
                .map(_ => handleConnect(attempt, connection))
                .fallbackTo { delayedStart() }
        } else {
            Future.failed (currentState match {
                case Connecting(_) => connectionInProgressException
                case Connected(_) => alreadyConnectedException
                case _ => connectionCancelledException
            })
        }
    }

    def start(): Future[Unit] = {
        log.info(s"connecting to $host:$port")

        state.get match {
            case Disconnected => connect(Disconnected)
            case Connected(_) => Future.failed(alreadyConnectedException)
            case _ => Future.failed(connectionInProgressException)
        }
    }

    def stop(): Boolean = {
        val current = state.get
        def exception = new Exception("Persistent connection stopped")

        state.set(Disconnected)

        current match {
            case Disconnected => false
            case Connected(conn) => conn.close(); true
            case _ => true
        }
    }

    def write(msg: Message, flush: Boolean = true): Boolean = {
        state.get match {
            case Connected(connection) => connection.write(msg, flush)
            case _ => false
        }
    }

    def flush(): Boolean = ifConnected {ctx => ctx.flush()}

    def close(): Boolean = ifConnected {ctx => ctx.close(); true}

    private def ifConnected(body: Connection => Boolean): Boolean = {
        state.get match {
            case Connected(ctx) => body(ctx)
            case _ => false
        }
    }

    private def ifTransition(current: State,
                             next: State)
                             (apply: => Unit): Boolean = {
        val replaced = state.compareAndSet(current, next)
        if (replaced) {
            apply
        }
        replaced
    }

    private def delayedStart(): Future[Unit] = {
        val promise = Promise[Unit]
        executor.schedule(new Runnable {
                def run() = {
                    promise.completeWith(delayedStartCallback())
                }
            }, retryTimeout.toMillis, MILLISECONDS)
        promise.future
    }

    private def delayedStartCallback(): Future[Unit] = {
        state.get match {
            case current @ Connecting(_) => connect(current)
            case _ => Future.failed(connectionInProgressException)
        }
    }
}

object PersistentConnection {

    private sealed trait State
    private case object Disconnected extends State
    private case class Connecting(id: Int) extends State
    private case class Connected(connection: Connection) extends State

    private val DefaultRetryDelay = 3 seconds
    private def connectionInProgressException =
        new IllegalStateException("Connection already in progress")
    private def alreadyConnectedException =
        new IllegalStateException("Already connected")
    private def connectionCancelledException =
        new CancellationException("Connection cancelled")
}