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

package org.midonet.cluster.services.state.server

import java.net.InetAddress
import java.util.concurrent.{Executors, ThreadFactory, TimeUnit}

import scala.concurrent.{Future, Promise}
import scala.util.{Failure, Success}

import com.typesafe.scalalogging.Logger

import io.netty.bootstrap.ServerBootstrap
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.nio.NioServerSocketChannel
import io.netty.channel.{Channel, ChannelOption}
import io.netty.handler.logging.{LogLevel, LoggingHandler}

import org.slf4j.LoggerFactory

import org.midonet.cluster._
import org.midonet.cluster.services.state.server.ChannelUtil._
import org.midonet.util.concurrent.{CallingThreadExecutionContext, NamedThreadFactory}
import org.midonet.util.functors.makeRunnable

/**
  * Implements a Netty server for the State Proxy service.
  */
class StateProxyServer(config: StateProxyConfig) {

    private val log = Logger(LoggerFactory.getLogger(stateProxyLog))

    private val mainExecutor = Executors.newSingleThreadScheduledExecutor(
        new ThreadFactory {
            override def newThread(runnable: Runnable): Thread = {
                val thread = new Thread(runnable, "state-proxy-main")
                thread.setDaemon(true)
                thread
            }
        })
    private val supervisorExecutor = Executors.newFixedThreadPool(
        supervisorThreads,
        new NamedThreadFactory("state-proxy-supervisor", isDaemon = true))
    private val workerExecutor = Executors.newFixedThreadPool(
        workerThreads,
        new NamedThreadFactory("state-proxy-worker", isDaemon = true))

    private val supervisorEventLoopGroup =
        new NioEventLoopGroup(supervisorThreads, supervisorExecutor)
    private val messageEventLoopGroup =
        new NioEventLoopGroup(workerThreads, workerExecutor)

    private val sync = new Object
    private val bootstrap = new ServerBootstrap

    private val serverChannelPromise = Promise[Channel]()
    private var serverShutdown: Boolean = false

    // Set the event loop groups for the server channels: the acceptor group
    // handles new connection requests, the message group handles I/O for
    // existing connections.
    bootstrap.group(supervisorEventLoopGroup, messageEventLoopGroup)

    // Options for the parent channel.
    bootstrap.option(ChannelOption.SO_REUSEADDR, Boolean.box(true))
    bootstrap.option(ChannelOption.SO_BACKLOG, Int.box(maxPendingConnections))

    // Options for the child channels: disable Nagle's algorithm for TCP to
    // improve latency, and sockets are closed asynchronously.
    bootstrap.childOption(ChannelOption.TCP_NODELAY, Boolean.box(true))
    bootstrap.childOption(ChannelOption.SO_LINGER, Int.box(-1))
    bootstrap.childOption(ChannelOption.SO_KEEPALIVE, Boolean.box(true))

    // Set logging.
    bootstrap.handler(new LoggingHandler(LogLevel.DEBUG))

    // Set the channel class.
    bootstrap.channel(classOf[NioServerSocketChannel])

    // Set the child handler.
    bootstrap.childHandler(new StateProxyClientInitializer)

    bootstrap.validate()

    bind()

    /**
      * Closes this server connection manager. The method is synchronous and
      * it awaits for all operations to complete according to the current
      * configuration.
      */
    def close(): Unit = {

        // Synchronize with the completion of the bind operation.
        sync.synchronized {
            serverShutdown = true
            serverChannel.value match {
                case Some(Success(channel)) =>
                    if (!channel.close()
                        .awaitUninterruptibly(config.serverChannelTimeout.toMillis,
                                              TimeUnit.MILLISECONDS)) {
                        log warn "Server channel failed to close within " +
                                 s"${config.serverChannelTimeout.toMillis} " +
                                 "milliseconds"
                    }
                case _ => // Server not yet bound: ignore
            }
        }

        val supervisorFuture = supervisorEventLoopGroup.shutdownGracefully(
            config.serverShutdownQuietPeriod.toMillis,
            config.serverShutdownTimeout.toMillis,
            TimeUnit.MILLISECONDS)
        val workerFuture = messageEventLoopGroup.shutdownGracefully(
            config.serverShutdownQuietPeriod.toMillis,
            config.serverShutdownTimeout.toMillis,
            TimeUnit.MILLISECONDS)

        if (!supervisorFuture.awaitUninterruptibly(
            config.serverShutdownTimeout.toMillis, TimeUnit.MILLISECONDS)) {
            log warn "Supervisor event loop failed to shutdown within " +
                     s"${config.serverShutdownTimeout.toMillis} milliseconds"
        }

        if (!workerFuture.awaitUninterruptibly(
            config.serverShutdownTimeout.toMillis, TimeUnit.MILLISECONDS)) {
            log warn "Worker event loop failed to shutdown within " +
                     s"${config.serverShutdownTimeout.toMillis} milliseconds"
        }

        workerExecutor.shutdown()
        supervisorExecutor.shutdown()
        mainExecutor.shutdown()

        if (!workerExecutor.awaitTermination(
            config.serverShutdownTimeout.toMillis, TimeUnit.MILLISECONDS)) {
            log warn "Worker executor failed to shutdown within " +
                     s"${config.serverShutdownTimeout.toMillis} milliseconds"
        }

        if (!supervisorExecutor.awaitTermination(
            config.serverShutdownTimeout.toMillis, TimeUnit.MILLISECONDS)) {
            log warn "Supervisor executor failed to shutdown within " +
                     s"${config.serverShutdownTimeout.toMillis} milliseconds"
        }

        if (!mainExecutor.awaitTermination(
            config.serverShutdownTimeout.toMillis, TimeUnit.MILLISECONDS)) {
            log warn "Server executor failed to shutdown within " +
                     s"${config.serverShutdownTimeout.toMillis} milliseconds"
        }
    }

    /**
      * @return A future that will complete with the server channel when the
      *         server binds to the local port.
      */
    private[server] def serverChannel: Future[Channel] = {
        serverChannelPromise.future
    }

    /**
      * @return The number of acceptor threads, or 1, if undefined.
      */
    private def supervisorThreads: Int = {
        if (config.serverSupervisorThreads > 0) config.serverSupervisorThreads
        else 1
    }

    /**
      * @return The number of message threads, or the minimum between the number
      *         of available processors and 4, if undefined.
      */
    private def workerThreads: Int = {
        if (config.serverWorkerThreads > 0) config.serverWorkerThreads
        else Integer.min(Runtime.getRuntime.availableProcessors(), 4)
    }

    /**
      * @return The maximum number of pending half-opened inbound connections
      *         that the server accepts.
      */
    private def maxPendingConnections: Int = {
        if (config.serverMaxPendingConnections > 0)
            config.serverMaxPendingConnections
        else 1024
    }

    /**
      * @return The address to which the server socket will bind.
      */
    private def serverAddress: InetAddress = {
        InetAddress.getByName(config.serverAddress)
    }

    private def bind(): Unit = {
        val address = serverAddress
        val port = config.serverPort

        log info s"Starting server at $address:$port..."
        bootstrap.bind(serverAddress, config.serverPort).asScala.onComplete {
            case Success(channel) =>
                log info s"Server started at $address:$port"
                // Synchronize with close: if the server was shutdown, close
                // the channel asynchronously.
                sync.synchronized {
                    if (serverShutdown) channel.close()
                    else serverChannelPromise trySuccess channel
                }
            case Failure(e) if config.serverBindRetryInterval.toSeconds > 0 =>
                log.warn(s"Failed to start server at $address:$port retrying " +
                         s"after ${config.serverBindRetryInterval.toSeconds} " +
                         s"second(s): ${e.getMessage}")
                mainExecutor.schedule(
                    makeRunnable { bind() },
                    config.serverBindRetryInterval.toSeconds, TimeUnit.SECONDS)
            case Failure(e) =>
                log.warn(s"Failed to start server at $address:$port", e)
                serverChannelPromise tryFailure e
        } (CallingThreadExecutionContext)
    }

}