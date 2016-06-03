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
import scala.PartialFunction.cond

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
abstract class PersistentConnection(host: String,
                                    port: Int,
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

    def isConnected: Boolean = cond(state.get) { case Connected(_) => true}

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
        onDisconnect(cause)
        val current = state.get
        current match {
            case Connected(_) =>
                val attempt = counter.incrementAndGet()
                if (state.compareAndSet(current,Connecting(attempt))) {
                    delayedStart(attempt)
                }
                else {
                    log.error("handleDisconnect: lost current state")
                }
            case _ =>
        }
    }

    private def handleConnect(attemptId: Int, connection: Connection): Unit = {
        try {
            val current = state.get
            current match {
                case Connecting(id) if id <= attemptId =>
                    if (state.compareAndSet(current, Connected(connection))) {
                        log.info("Connection established")
                        onConnect()
                    } else {
                        throw connectionCancelledException
                    }
                case Connecting(_) =>
                    // there is a higher-priority connect in process,
                    // silently close this connection
                    connection.close()

                case Connected(_) => throw alreadyConnectedException
                case Disconnected => throw connectionCancelledException
                case _ => throw connectionInProgressException
            }
        } catch {
            // close this connection if cancelled
            case err: Throwable => connection.close()
                                   throw err
        }
    }

    private def connect(currentState: State): Future[Unit] = {
        val attempt = counter.incrementAndGet()

        if (state.compareAndSet(currentState, Connecting(attempt))) {
            // now we are the owners of this connecting state
            // no two connection attempts can be alive now, but we
            // can still be cancelled by stop()
            val connection = new Connection(host,
                                            port,
                                            this,
                                            ProxyResponse.getDefaultInstance)
            connection.connect()
                .map(_ => handleConnect(attempt, connection))
                .recoverWith{ case _ => delayedStart(attempt) }
        } else {
            val newState = state.get
            Future.failed (newState match {
                case Connecting(_) => connectionInProgressException
                case Connected(_) => alreadyConnectedException
                case _ => connectionCancelledException
            })
        }
    }

    def start(): Future[Unit] = {
        log.info(s"Connecting to $host:$port")
        state.get match {
            case Disconnected => connect(Disconnected)
            case Connected(_) => Future.failed(alreadyConnectedException)
            case _ => Future.failed(connectionInProgressException)
        }
    }

    def stop(): Boolean = {
        log.info("Stop requested")

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

    def flush(): Boolean = state.get match {
            case Connected(ctx) => ctx.flush()
            case _ => false
        }

    private def delayedStart(attempt: Int): Future[Unit] = {
        val promise = Promise[Unit]
        executor.schedule(new Runnable {
                def run() = {
                    state.get match {
                        case current @ Connecting(attempt) =>
                            promise.completeWith(connect(current))
                        case _ => promise.failure(connectionCancelledException)
                    }
                }
            }, retryTimeout.toMillis, MILLISECONDS)
        promise.future
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