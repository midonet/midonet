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
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.ScheduledExecutorService

import scala.util.{Failure, Success}
import scala.PartialFunction.cond
import scala.concurrent.duration._
import scala.concurrent.{CancellationException, ExecutionContext}

import com.google.protobuf.Message
import com.typesafe.scalalogging.Logger

import io.netty.channel.nio.NioEventLoopGroup

import org.slf4j.LoggerFactory

import rx.Observer

/**
  * The PersistentConnection abstract class will maintain a persistent
  * connection to a remote server by means of the [[Connection]] class.
  * When the connection is dropped (due to a close by the remote server,
  * or an error) it will try to reconnect indefinitely.
  *
  * Type parameters S and R stand for Send and Receive types, respectively. See
  * [[Connection]].
  *
  * @param host the remote server host
  * @param port the remote port to connect to
  * @param executor ScheduledExecutorService used to schedule reconnects
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
  * mechanism.
  */
abstract class PersistentConnection[S <: Message, R <: Message]
                                   (name: String,
                                    host: String,
                                    port: Int,
                                    executor: ScheduledExecutorService,
                                    retryTimeout: FiniteDuration
                                        = PersistentConnection.DefaultRetryDelay)
                                   (implicit context: ExecutionContext,
                                    eventLoopGroup: NioEventLoopGroup)
        extends Observer[R] {

    import PersistentConnection._

    private type ConnectionType = Connection[S, R]

    private case class Connected(connection: ConnectionType) extends State

    private val log = Logger(LoggerFactory.getLogger("PersistentConnection"))
    private val state = new AtomicReference(Init : State)

    def isConnected: Boolean = cond(state.get) { case Connected(_) => true }
    def isConnecting: Boolean = cond(state.get) {
        case AwaitingReconnect | Connecting => true
    }

    /** Implement this method to provide the prototype message for decoding.
      * Usually [[R]].getDefaultInstance
      *
      * @return The prototype message
      */
    protected def getMessagePrototype: R

    /**
      * Implement this method to add custom behavior when the connection
      * is established (for the first time or after a connection retry)
      */
    protected def onConnect(): Unit

    /**
      * Implement this method to add custom behavior when the connection
      * is closed (due to stop(), remote close(), or an error)
      */
    protected def onDisconnect(cause: Throwable): Unit

    /**
      * Initiates the connection to the remote server. It will retry
      * indefinitely until successful
      *
      * @throws StoppedException
      * @throws AlreadyStartedException
      */
    @throws[StoppedException]
    @throws[AlreadyStartedException]
    def start(): Unit = {
        state.get match {
            case Init =>
                if (state.compareAndSet(Init, AwaitingReconnect)) {
                    connect()
                } else {
                    // match over new state and return appropriate error
                    start()
                }
            case Dead =>
                throw new StoppedException(toString)

            case _ =>
                throw new AlreadyStartedException(toString)
        }
    }

    def stop(): Boolean = {
        log.info(s"$this disconnecting")

        state.getAndSet(Dead) match {
            case Dead => false
            case Connected(conn) =>
                conn.close()
                onDisconnect(new StoppedException(toString))
                true
            case _ => true
        }
    }

    def write(msg: S, flush: Boolean = true): Boolean = state.get match {
        case Connected(connection) => connection.write(msg, flush)
        case _ => false
    }

    def flush(): Boolean = state.get match {
        case Connected(connection) => connection.flush()
        case _ => false
    }

    final protected def onCompleted(): Unit =
        handleDisconnect(new ClosedChannelException())

    final protected def onError(cause: Throwable): Unit =
        handleDisconnect(cause)

    private def handleDisconnect(cause: Throwable) = {
        log.warn(s"$this closed: $cause")

        val current = state.get
        current match {
            case Connected(_) =>
                onDisconnect(cause)
                delayedStart(current)

            case Dead =>

            case _ =>
                throw new UnexpectedStateException(current, toString)
        }
    }

    private def handleConnect(connection: ConnectionType): Unit = {
        try {
            state.get match {
                case Connecting =>
                    if (state.compareAndSet(Connecting, Connected(connection))) {
                        log.info(s"$this connection established")
                        onConnect()
                    } else {
                        throw new StoppedException(toString)
                    }
                case Dead => throw new StoppedException(toString)
                case other: State =>
                    throw new UnexpectedStateException(other, toString)
            }
        } catch {
            case err: Throwable =>
                // cleanup
                connection.close()
                throw err
        }
    }

    @throws[StoppedException]
    @throws[UnexpectedStateException]
    private def connect(): Unit = {
        log.info(s"$this connecting")

        if (state.compareAndSet(AwaitingReconnect, Connecting)) {
            val connection = new ConnectionType(host,
                                                port,
                                                this,
                                                getMessagePrototype)
            connection.connect() onComplete {
                case Success(_)   => handleConnect(connection)
                case Failure(err) => delayedStart(Connecting)
            }
        } else {
            val newState = state.get
            throw newState match {
                case Dead => new StoppedException(toString)
                case _ => new UnexpectedStateException(newState, toString)
            }
        }
    }

    private def delayedStart(currentState: State): Unit = {
        if (state.compareAndSet(currentState, AwaitingReconnect)) {
            executor.schedule(new Runnable {
                def run() = {
                    try {
                        connect()
                    }
                    catch {
                        case err: Throwable =>
                            log.info(s"$this reconnect cancelled: $err")
                    }
                }
            }, retryTimeout.toMillis, MILLISECONDS)
        } else {
            log.info(s"$this reconnect cancelled.")
        }
    }

    override def toString: String = s"[$name to $host:$port]"
}

object PersistentConnection {

    private sealed trait State
    private case object Init extends State
    private case object AwaitingReconnect extends State
    private case object Connecting extends State
    private case object Dead extends State

    private val DefaultRetryDelay = 3 seconds

    class UnexpectedStateException(s: State, desc: String)
        extends IllegalStateException(s"$desc got caught in an unexpected state: $s")

    class AlreadyStartedException(desc: String)
        extends IllegalStateException(s"$desc has already been started")

    class StoppedException(desc: String)
        extends CancellationException(s"$desc has been stopped")
}