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

import com.google.protobuf.Message
import io.netty.channel.nio.NioEventLoopGroup
import rx.Observer
import org.midonet.cluster.rpc.State
import org.slf4j.LoggerFactory
import com.typesafe.scalalogging.Logger

import scala.concurrent.duration._
import scala.concurrent.{Future, Promise, ExecutionContext, blocking}
import scala.util.{Failure, Success}

object PersistentConnection {
    val DEFAULT_RETRY_DELAY = 3 seconds
}

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
  * Implement the missing Observer[Message] method: [[onNext(msg)]]
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
                                    retryTimeout: FiniteDuration = PersistentConnection.DEFAULT_RETRY_DELAY)
                                   (implicit ec: ExecutionContext) extends Observer[Message] {

    private val log = Logger(LoggerFactory.getLogger(classOf[PersistentConnection]))

    /**
      * use this member to [[write()]] messages to the remote server
      */
    protected var connection: Option[Connection] = None

    private var connectPromise : Option[Promise[AnyRef]] = None
    private val sync = new Object

    def connected: Boolean = sync.synchronized {
        connection.isDefined && connection.get.isConnected
    }

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
      * is stablished (for the first time or after a connection retry)
      */
    protected def onConnect(): Unit = {}

    /**
      * Override this method to implement custom behavior when the connection
      * is closed (due to stop(), local or remote close(), or an error)
      */
    protected def onDisconnect(cause: Throwable): Unit = {}

    final protected def onCompleted(): Unit =
        handleDisconnect(new Exception("Connection closed by remote server"))

    final protected def onError(cause: Throwable): Unit =
        handleDisconnect(cause)

    private def handleDisconnect(cause: Throwable) = {
        log.warn(s"Disconnected due to: $cause")
        replaceConnection(None)
        onDisconnect(cause)
        scheduleReconnect()
    }

    def start(): Future[AnyRef] = {
        log.info(s"connecting to $host:$port")
        sync.synchronized {
            if ( ! connected ) {
                if (connectPromise.isEmpty || connectPromise.get.isCompleted) {
                    connectPromise = Some(Promise[AnyRef])
                    connection = Some(connectionFactory(host,port,
                                                        this,eventLoopGroup)(ec))

                    connection.get.connect() onComplete {
                        case Success(newClient) => log.info("connected")
                            replaceConnection(Some(newClient))
                            connectPromise.get.trySuccess(this)
                            onConnect()

                        case Failure(err) => connectPromise.get.tryFailure(err)
                            log.warn(s"Connection failed: $err")
                            replaceConnection(None)
                            scheduleReconnect()
                    }
                    connectPromise.get.future
                }
                else {
                    val reason = new Exception("Connection already in progress")
                    log.warn(s"Cannot connect: $reason")
                    Promise[AnyRef].failure(reason).future
                }
            }
            else {
                val reason = new Exception("Already connected")
                log.warn(s"Cannot connect: $reason")
                Promise[AnyRef].failure(reason).future
            }
        }
    }

    def stop(): Unit = {
        log.info("stopping")
        sync.synchronized {
            if (connectPromise.isDefined && !connectPromise.get.isCompleted) {
                connectPromise.get.tryFailure(new Exception("Shutting down"))
                connectPromise = None
            }
            replaceConnection(None)
        }
        onDisconnect(new Exception("stopped"))
    }

    private def replaceConnection(newConn: Option[Connection]) = {
        sync.synchronized {
            if (connection.isDefined && connection != newConn) {
                connection.get.close()
            }
            connection = newConn
        }
    }

    private def scheduleReconnect(): Unit = {
        Future{
            // TODO: Improve
            blocking { Thread.sleep(retryTimeout.toMillis) }
            start()
        }
    }
}
