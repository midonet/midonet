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
import scala.util.control.NonFatal

import com.google.protobuf.Message
import com.typesafe.scalalogging.Logger

import io.netty.channel.nio.NioEventLoopGroup

import org.slf4j.LoggerFactory

import rx.Observer

import org.midonet.cluster.services.discovery.MidonetServiceHostAndPort

/**
  * The PersistentConnection abstract class will maintain a persistent
  * connection to a remote server by means of the [[Connection]] class.
  * When the connection is dropped (due to a close by the remote server,
  * or an error) it will try to reconnect indefinitely.
  *
  * Type parameters S and R stand for Send and Receive types, respectively. See
  * [[Connection]].
  *
  * @param executor ScheduledExecutorService used to schedule reconnects
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
                                    executor: ScheduledExecutorService,
                                    connectTimeout: Duration)
                                   (implicit context: ExecutionContext,
                                    eventLoopGroup: NioEventLoopGroup)
        extends Observer[R] {

    import PersistentConnection._

    private type ConnectionType = Connection[S, R]

    private case class Connected(connection: ConnectionType) extends State {
        override def toString = s"connected $connection"
    }

    protected val log =
        Logger(LoggerFactory.getLogger("org.midonet.nsdb.state-proxy-client"))
    private val state = new AtomicReference(Init : State)
    private var currentAddress: Option[MidonetServiceHostAndPort] = None

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

    /** This method allows to provide the target host and port to connect to.
      * Will be called on every connect() so different values can be returned
      * i.e. from service discovery
      *
      * @return host and port
      */
    protected def getRemoteAddress: Option[MidonetServiceHostAndPort]

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
      * Implement this method to react to connection failures
      */
    protected def onFailedConnection(cause: Throwable): Unit

    /**
      * Implement this method to provide the reconnection delay
      */
    protected def reconnectionDelay: Duration

    /**
      * Initiates the connection to the remote server. It will retry
      * indefinitely until successful
      *
      * @throws StoppedException When already stopped
      * @throws AlreadyStartedException When already started
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
                throw new StoppedException()

            case _ =>
                throw new AlreadyStartedException()
        }
    }

    def stop(): Boolean = {
        log.info(s"$this Disconnecting")

        state.getAndSet(Dead) match {
            case Dead => false
            case Connected(conn) =>
                conn.close()
                onDisconnect(new StoppedException())
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
        log.warn(s"$this Connection closed: ${cause.getMessage}")

        val current = state.get
        current match {
            case Connected(_) =>
                onDisconnect(cause)
                delayedStart(current)

            case Dead =>

            case Connecting =>
                log debug s"Detected early close"

            case _ =>
                throw new UnexpectedStateException(current)
        }
    }

    @throws[StoppedException]
    @throws[UnexpectedStateException]
    private def connect(): Unit = {
        if (state.compareAndSet(AwaitingReconnect, Connecting)) {
            currentAddress = getRemoteAddress
            currentAddress match {

                case Some(address) =>
                    log.info(s"$this Connecting to $address")

                    val connection = new ConnectionType(address.address,
                                                        address.port,
                                                        this,
                                                        getMessagePrototype,
                                                        connectTimeout)
                    connection.connect() onComplete {
                        case Success(_) =>
                            val newState = Connected(connection)
                            if (state.compareAndSet(Connecting, newState)) {
                                // if a close event arrived while in Connecting
                                // state, it needs to be fired after onConnect
                                // to ensure that a re-connection is scheduled
                                if (connection.isConnected) {
                                    log.info(s"$this Connection established")
                                    onConnect()
                                } else {
                                    onFailedConnection(new ClosedChannelException)
                                    delayedStart(newState)
                                }
                            } else {
                                connection.close()
                            }
                        case Failure(err) =>
                            onFailedConnection(err)
                            delayedStart(Connecting)
                    }

                case None =>
                    log.warn(s"$this No state proxy server available")
                    onFailedConnection(new ServerNotFoundException())
                    delayedStart(Connecting)
            }
        } else {
            state.get match {
                case Dead => throw new StoppedException()
                case s: State => throw new UnexpectedStateException(s)
            }
        }
    }

    private def delayedStart(currentState: State): Unit = {
        if (state.compareAndSet(currentState, AwaitingReconnect)) {
            log debug s"$this Reconnecting to state proxy server in " +
                      s"${reconnectionDelay.toMillis} milliseconds"
            executor.schedule(new Runnable {
                def run() = {
                    try {
                        connect()
                    } catch {
                        case NonFatal(e) =>
                            log.debug(s"$this Reconnect cancelled: " +
                                      s"${e.getMessage}")
                    }
                }
            }, reconnectionDelay.toMillis, MILLISECONDS)
        } else {
            log.info(s"$this Reconnect cancelled")
        }
    }

    override def toString: String = s"[$state]"
}

object PersistentConnection {

    private sealed trait State
    private case object Init extends State {
        override def toString = "init"
    }
    private case object AwaitingReconnect extends State {
        override def toString = "awaiting"
    }
    private case object Connecting extends State {
        override def toString = "connecting"
    }
    private case object Dead extends State {
        override def toString = "dead"
    }

    class UnexpectedStateException(s: State)
        extends IllegalStateException(s"client in an unexpected state: $s")

    class AlreadyStartedException
        extends IllegalStateException("client has already been started")

    class StoppedException
        extends CancellationException("client has been stopped")

    class ServerNotFoundException
        extends Exception("state proxy server not found")
}