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

import java.net.{Inet4Address, InetAddress, InetSocketAddress, NetworkInterface}
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.{Executors, ScheduledFuture, ThreadFactory, TimeUnit}

import scala.collection.JavaConverters._
import scala.concurrent.{Future, Promise}
import scala.util.control.NonFatal
import scala.util.{Failure, Success}

import com.typesafe.scalalogging.Logger

import io.netty.bootstrap.ServerBootstrap
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.nio.NioServerSocketChannel
import io.netty.channel.{Channel, ChannelFuture, ChannelOption}
import io.netty.handler.logging.{LogLevel, LoggingHandler}

import org.apache.commons.lang3.StringUtils
import org.slf4j.LoggerFactory

import org.midonet.cluster._
import org.midonet.cluster.services.discovery.{MidonetDiscovery, MidonetServiceHandler}
import org.midonet.cluster.services.state.server.ChannelUtil._
import org.midonet.cluster.services.state.server.StateProxyServer._
import org.midonet.cluster.services.state.{StateProxyService, StateTableManager}
import org.midonet.util.concurrent.{CallingThreadExecutionContext, NamedThreadFactory}
import org.midonet.util.functors.makeRunnable

object StateProxyServer {

    /**
      * Represents the server channel state.
      */
    private sealed trait State

    /**
      * Initial server state: no channel.
      */
    private case object Init extends State

    /**
      * Server has initiated an asynchronous binding to the socket.
      *
      * @param future The channel future indicating when the binding has
      *               completed. It allows canceling the operation.
      */
    private case class Binding(future: ChannelFuture) extends State

    /**
      * The server socket is bound and a channel is open.
      */
    private case class Bound(channel: Channel) extends State

    /**
      * Server has scheduled a retry after a failed binding attempt.
      *
      * @param future The future of the retry operation.
      */
    private case class Retry(future: ScheduledFuture[_]) extends State


    /**
      * The server has shut down.
      */
    private case object ShutDown extends State

    private val AnyLocalAddress = new InetSocketAddress(0).getAddress

}

/**
  * Implements a Netty server for the State Proxy service.
  */
class StateProxyServer(config: StateProxyConfig, manager: StateTableManager,
                       discovery: MidonetDiscovery) {

    private val log = Logger(LoggerFactory.getLogger(StateProxyLog))

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

    private val state = new AtomicReference[State](Init)
    private val bootstrap = new ServerBootstrap

    private val serverChannelPromise = Promise[Channel]()

    @volatile private var serviceHandler: MidonetServiceHandler = _

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
    bootstrap.childHandler(new StateProxyClientInitializer(manager))

    bootstrap.validate()

    bind()

    /**
      * Closes this server connection manager. The method is synchronous and
      * it awaits for all operations to complete according to the current
      * configuration.
      */
    def close(): Unit = {

        unregisterDiscovery()

        // Synchronize with the completion of the bind operation.
        var oldState: State = null
        do {
            oldState = state.get
            oldState match {
                case Binding(future) =>
                    future.cancel(false)
                case Bound(channel) =>
                    if (!channel.close()
                        .awaitUninterruptibly(
                            config.serverChannelTimeout.toMillis,
                            TimeUnit.MILLISECONDS)) {
                        log warn "Server channel failed to close within " +
                                 s"${config.serverChannelTimeout.toMillis} " +
                                 "milliseconds"
                    }
                case Retry(future) =>
                    future.cancel(false)
                case _ => // Ignore
            }
        } while (!state.compareAndSet(oldState, ShutDown))

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
      * @return The number of message threads, or 4, if undefined.
      */
    private def workerThreads: Int = {
        if (config.serverWorkerThreads > 0) config.serverWorkerThreads else 4
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
    private def serverAddress: Option[InetAddress] = {

        def isValid(address: InetAddress): Boolean = {
            address.isInstanceOf[Inet4Address] &&
            !address.isAnyLocalAddress &&
            !address.isLinkLocalAddress &&
            !address.isLoopbackAddress &&
            !address.isMulticastAddress
        }

        val address =
            if (StringUtils.isNotBlank(config.serverAddress))
                InetAddress.getByName(config.serverAddress)
            else
                StateProxyServer.AnyLocalAddress

        if (StringUtils.isNotBlank(config.serverInterface)) {
            // Use an address of the specified interface.
            val interface =
                try NetworkInterface.getByName(config.serverInterface)
                catch { case NonFatal(_) => null }
            if (interface ne null) {
                val addresses = interface.getInetAddresses.asScala
                    .filter(isValid).toSet
                if (!address.isAnyLocalAddress) {
                    if (addresses.contains(address)) {
                        // Use the configured address for the interface.
                        log info s"Using IP address ${address.getHostAddress} " +
                                 s"on network interface ${interface.getName}"
                        Some(address)
                    } else {
                        log warn s"Network interface ${interface.getName} " +
                                 "is not configured with IP address " +
                                 s"${address.getHostAddress}: state proxy " +
                                 "server disabled. To resolve this issue, " +
                                 "update state_proxy.address and/or " +
                                 "state_proxy.interface in mn-conf"
                        None
                    }
                } else {
                    if (addresses.size == 1) {
                        log info s"Using IP address ${addresses.head.getHostAddress} " +
                                 s"on network interface ${interface.getName}"
                        Some(addresses.head)
                    } else if (addresses.isEmpty) {
                        log warn s"Network interface ${interface.getName} " +
                                 "requires at least one non-loopback unicast " +
                                 "IPv4 address: state proxy server disabled. " +
                                 "To resolve this issue, configure an IPv4 " +
                                 "address or update state_proxy.interface " +
                                 "in mn-conf"
                        None
                    } else {
                        log warn s"Network interface ${interface.getName} " +
                                 "has multiple non-loopback unicast IPv4 " +
                                 s"addresses: ${addresses.map(_.getHostAddress)}: " +
                                 "state proxy server disabled. To resolve " +
                                 "this issue, update state_proxy.address " +
                                 "and/or state_proxy.interface in mn-conf"
                        None
                    }
                }
            } else {
                log warn s"Network interface ${config.serverInterface} not " +
                         "found: state proxy server disabled. To resolve " +
                         "this issue, update state_proxy.address " +
                         "and/or state_proxy.interface in mn-conf"
                None
            }
        } else if (!address.isAnyLocalAddress) {
            // Address is specific, and interface not specified: use the
            // address.
            log info s"Using IP address ${address.getHostName}"
            Some(address)
        } else {
            // Use any local address.
            val addresses = NetworkInterface.getNetworkInterfaces.asScala
                .filter(interface => interface.isUp && !interface.isLoopback)
                .flatMap(_.getInetAddresses.asScala)
                .filter(isValid)
                .toList

            if (addresses.isEmpty) {
                log warn "No IP address available: state proxy server disabled"
                None
            } else if (addresses.size > 1) {
                log warn "Host has multiple non-loopback unicast IPv4 addresses " +
                         "and requires a specific configuration: state proxy " +
                         "server disabled. To resolve this issue, set " +
                         "state_proxy.address and/or state_proxy.interface " +
                         "in mn-conf"
                None
            } else {
                log info s"Using IP address ${addresses.head.getHostName}"
                Some(addresses.head)
            }
        }
    }

    /**
      * Binds a new channel to the current server address and port.
      */
    private def bind(): Unit = {
        val address = serverAddress match {
            case Some(a) => a
            case None => return
        }
        val port = config.serverPort

        log info s"Starting server at $address:$port..."

        val channelFuture = bootstrap.bind(address, port)
        if (!state.compareAndSet(Init, Binding(channelFuture))) {
            channelFuture.asScala.onComplete {
                case Success(channel) => channel.close()
                case _ => // Ignore
            }(CallingThreadExecutionContext)
            channelFuture.cancel(false)
        } else {
            channelFuture.asScala.onComplete {
                case Success(channel) =>
                    bindCompleted(address, port, channel)
                case Failure(e) if config.serverBindRetryInterval.toSeconds > 0 =>
                    bindRetry(address, port, e)
                case Failure(e) =>
                    bindFailed(address, port, e)
                case _ => // Ignore
            }(CallingThreadExecutionContext)
        }
    }

    /**
      * Handles a successful completion of the channel bind.
      */
    private def bindCompleted(address: InetAddress, port: Int,
                              channel: Channel): Unit = {
        log info s"Server started at $address:$port"

        registerDiscovery(address, port)

        val oldState = state.get()
        oldState match {
            case Binding(_) =>
                if (state.compareAndSet(oldState, Bound(channel))) {
                    serverChannelPromise trySuccess channel
                }
            case _ =>
                channel.close()
                serverChannelPromise tryFailure
                    new IllegalStateException("Server shut down")
        }
    }

    /**
      * Handles a failure of the channel bind and scheduled a retry.
      */
    private def bindRetry(address: InetAddress, port: Int,
                          e: Throwable): Unit = {
        log.warn(s"Failed to start server at $address:$port retrying " +
                 s"after ${config.serverBindRetryInterval.toSeconds} " +
                 s"second(s): ${e.getMessage}")
        val oldState = state.get()
        oldState match {
            case Binding(_) =>
                val future = mainExecutor.schedule(
                    makeRunnable { retry() },
                    config.serverBindRetryInterval.toSeconds, TimeUnit.SECONDS)
                if (!state.compareAndSet(oldState, Retry(future))) {
                    future.cancel(false)
                }
            case _ => // Ignore
        }
    }

    /**
      * Handles a failure of the bind that cannot recover. The server will
      * transition in [[ShutDown]] state.
      */
    private def bindFailed(address: InetAddress, port: Int,
                           e: Throwable): Unit = {
        log.warn(s"Failed to start server at $address:$port", e)
        val oldState = state.get()
        oldState match {
            case Binding(_) =>
                if (state.compareAndSet(oldState, ShutDown)) {
                    serverChannelPromise tryFailure e
                }
            case _ => // Ignore
        }
    }

    /**
      * Handles a retry operation. If the current state is [[Retry]] the server
      * transitions into an [[Init]] state and calls the [[bind()]] method.
      */
    private def retry(): Unit = {
        val address = serverAddress
        val port = config.serverPort

        log debug s"Retrying to bind server at $address:$port"
        val oldState = state.get()
        oldState match {
            case Retry(_) =>
                if (state.compareAndSet(oldState, Init)) {
                    bind()
                }
            case _ => // Ignore
        }
    }

    /**
      * Registers this state proxy server to service discovery with the
      * specified address and port.
      */
    private def registerDiscovery(address: InetAddress, port: Int): Unit = {

        log debug s"Registering ${address.getHostAddress}:$port to " +
                  s"service discovery for service ${StateProxyService.Name}"
        serviceHandler = discovery
            .registerServiceInstance(StateProxyService.Name,
                                     address.getHostAddress, port)
    }

    /**
      * Unregisters this state proxy server from service discovery.
      */
    private def unregisterDiscovery(): Unit = {
        if (serviceHandler ne null) {
            serviceHandler.unregister()
            serviceHandler = null
        }
    }

}