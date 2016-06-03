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

import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.atomic.{AtomicInteger, AtomicReference}

import com.google.protobuf.Message
import io.netty.channel.nio.NioEventLoopGroup
import rx.Observer
import org.midonet.cluster.rpc.State
import org.slf4j.LoggerFactory
import com.typesafe.scalalogging.Logger

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future, Promise, blocking}

/**
  * The PersistentConnection abstract class will maintain a persistent
  * connection to a remote server by means of the [[Connection]] class.
  * When the connection is dropped (due to a close by the remote server,
  * or an error) it will try to reconnect indefinitely.
  *
  * @param host the remote server host
  * @param port the remote port to connect to
  * @param eventLoopGroup is the [[NioEventLoopGroup]] to be used for Netty's
  *                       event loop.
  * @param retryTimeout the time to wait between reconnect attempts.
  *                     The default is 3 seconds.
  *
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
                                    eventLoopGroup: NioEventLoopGroup,
                                    executor: ScheduledThreadPoolExecutor,
                                    retryTimeout: FiniteDuration = PersistentConnection.defaultRetryDelay)
                    (implicit ec: ExecutionContext) extends Observer[Message] {

    import PersistentConnection._

    private val log = Logger(LoggerFactory.getLogger(classOf[PersistentConnection]))

    //protected var connection: Option[Connection] = None
    //private var connectPromise : Option[Promise[AnyRef]] = None
    //private val sync = new Object
    private val state = new AtomicReference(Disconnected() : LogicalState)
    private val counter = new AtomicInteger(0)

    /**
      * Override this method to have a custom source for Connection instances.
      * Useful for mocks.
      */
    protected def connectionFactory(host: String,
                                    port: Int,
                                    subscriber: Observer[Message],
                                    eventLoopGroup: NioEventLoopGroup)
                                   (implicit ec: ExecutionContext)

                : Connection = new Connection(host, port, this,
                                              State.Message.getDefaultInstance,
                                              eventLoopGroup)

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
        handleDisconnect(new Exception("Connection closed by remote server"))

    final protected def onError(cause: Throwable): Unit = handleDisconnect(cause)

    private def handleDisconnect(cause: Throwable) = {
        log.warn(s"Disconnected due to: $cause")
        val current = state.get()
        current match {
            case Connected(conn) => reconnect(current)
            case _ => log.warn(s"Disconnect in unexpected state $current")
        }
        onDisconnect(cause)
        delayedStart()
    }

    private def connectHandler(attemptId: Int, connection: Connection) = {
        val current = state.get()
        current match {
            case Connecting(id) if id == attemptId =>
                if (state.compareAndSet(current, Connected(connection))) {
                    onConnect()
                } else {
                    throw exceptionConnectionCancelled
                }
                this

            case Connected(_) => throw exceptionAlreadyConnected
            case Disconnected() => throw exceptionConnectionCancelled
            case _ => throw exceptionConnectionInProgress
        }
    }

    private def reconnect(currentState: LogicalState): Future[PersistentConnection] = {
        val attempt = counter.incrementAndGet()
        val guardState = Connecting(attempt)
        if (state.compareAndSet(currentState,guardState)) {
            // now we are the owners of this connecting state
            // no two connection attemps can be alive now, but we
            // can still be cancelled by stop()
            connectionFactory(host, port, this, eventLoopGroup)(ec)
                .connect()
                .map(connection => connectHandler(attempt,connection))
                .fallbackTo(delayedStart())
        } else {
            Promise.failed(exceptionConnectionInProgress).future
        }
    }

    def start(): Future[PersistentConnection] = {
        log.info(s"connecting to $host:$port")

        state.get() match {
            case current @ Disconnected() => reconnect(current)
            case Connected(_) => Promise.failed(exceptionAlreadyConnected).future
            case _ => Promise.failed(exceptionConnectionInProgress).future
        }
    }

    def stop(): Boolean = {
        val current = state.get()
        val exception = new Exception("stopped")

        current match {
            case Disconnected() => true

            case Connected(conn) =>
                ifTransition(current, Disconnected()){
                    conn.close()
                    onDisconnect(exception)
                }

            case _ =>
                ifTransition(current,Disconnected()) {
                    onDisconnect(exception)
                }
        }
    }

    def write(msg: Message, flush: Boolean = true): Boolean = {
        state.get() match {
            case Connected(connection) => connection.write(msg,flush)
            case _ => false
        }
    }

    def flush(): Boolean = ifConnected( ctx => ctx.flush() )

    def close(): Boolean = ifConnected( ctx => ctx.close() )

    private def ifConnected(body: Connection => Boolean): Boolean = {
        state.get() match {
            case Connected(ctx) => body(ctx)
            case _ => false
        }
    }

    private def ifTransition(current: LogicalState,
                            next: LogicalState)
                           (apply: => Unit): Boolean = {
        val replaced = state.compareAndSet(current,next)
        if (replaced) {
            apply
        }
        replaced
    }

    private def delayedStart(): Future[PersistentConnection] = {
        val promise = Promise[PersistentConnection]()
        executor.schedule(new Runnable {
                def run() = {
                    promise.completeWith(delayedStartCallback())
                }
            },retryTimeout.toMillis,MILLISECONDS)
        promise.future
    }

    private def delayedStartCallback(): Future[PersistentConnection] = {
        state.get() match {
            case current @ Connecting(_) => reconnect(current)
            case _ => Promise().failure(exceptionConnectionInProgress).future
        }
    }
}

object PersistentConnection {
    sealed trait LogicalState
    case class Disconnected() extends LogicalState
    case class Connecting(id: Int) extends LogicalState
    case class Connected(connection: Connection) extends LogicalState

    val defaultRetryDelay = 3 seconds
    val exceptionConnectionInProgress = new Exception("Connection already in progress")
    val exceptionAlreadyConnected = new Exception("Already connected")
    val exceptionConnectionCancelled = new Exception("Connection cancelled")
}