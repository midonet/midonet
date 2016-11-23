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

package org.midonet.cluster.services

import java.util.concurrent.{ExecutorService, ScheduledExecutorService, ScheduledFuture, TimeUnit}

import scala.collection.JavaConversions._
import scala.concurrent.ExecutionContext
import scala.util.control.NonFatal

import com.codahale.metrics.MetricRegistry
import com.typesafe.scalalogging.Logger

import io.netty.channel.nio.NioEventLoopGroup

import org.apache.curator.framework.CuratorFramework
import org.apache.curator.framework.imps.CuratorFrameworkState
import org.apache.curator.framework.state.ConnectionState
import org.reflections.Reflections
import org.slf4j.LoggerFactory

import rx.{Observable, Subscriber}

import org.midonet.cluster.data.storage._
import org.midonet.cluster.data.storage.metrics.StorageMetrics
import org.midonet.cluster.data.{ZoomInit, ZoomInitializer}
import org.midonet.cluster.rpc.State.ProxyResponse.Notify
import org.midonet.cluster.services.discovery.{MidonetDiscovery, MidonetDiscoveryImpl}
import org.midonet.cluster.services.state.StateProxyService
import org.midonet.cluster.services.state.client.StateTableClient.ConnectionState.{ConnectionState => StateClientConnectionState}
import org.midonet.cluster.services.state.client.{StateProxyClient, StateSubscriptionKey, StateTableClient}
import org.midonet.cluster.storage.MidonetBackendConfig
import org.midonet.cluster.util.ConnectionObservable
import org.midonet.conf.HostIdGenerator
import org.midonet.util.concurrent.Executors
import org.midonet.util.eventloop.TryCatchReactor
import org.midonet.util.functors.makeRunnable

/** Class responsible for providing services to access to the new Storage
  * services. */
class MidonetBackendService(config: MidonetBackendConfig,
                            override val curator: CuratorFramework,
                            override val failFastCurator: CuratorFramework,
                            metricRegistry: MetricRegistry,
                            reflections: Option[Reflections])
    extends MidonetBackend {

    private val log = Logger(LoggerFactory.getLogger("org.midonet.nsdb"))

    private val namespaceId =
        if (MidonetBackend.isCluster) MidonetBackend.ClusterNamespaceId
        else HostIdGenerator.getHostId

    private var discoveryServiceExecutor: ExecutorService = null
    private var discoveryService: MidonetDiscovery = null
    private var stateProxyClientExecutor : ScheduledExecutorService = null
    @volatile private var stateProxyClient: StateProxyClient = null

    override val reactor = new TryCatchReactor("nsdb", 1)
    override val connectionState =
        ConnectionObservable.create(curator)
    override val failFastConnectionState =
        ConnectionObservable.create(failFastCurator)

    private val stateTableClientWrapper = new StateTableClient {
        override def stop(): Boolean = false

        override def observable(key: StateSubscriptionKey)
        : Observable[Notify.Update] = {
            val client = stateProxyClient
            if (client ne null) client.observable(key)
            else Observable.never()
        }

        override def connection: Observable[StateClientConnectionState] = {
            val client = stateProxyClient
            if (client ne null) client.connection
            else Observable.never()
        }

        override def start(): Unit = { }
    }

    private val connectionSubscriber = new Subscriber[ConnectionState] {
        override def onNext(state: ConnectionState): Unit = {
            connectionStateChanged(state)
        }

        override def onCompleted(): Unit = {
            log warn s"Backend connection notification stream has completed"
        }

        override def onError(e: Throwable): Unit = {
            log.error("Error on connection notification stream", e)
        }
    }
    private var connectionSuspendedTime = -1L
    private var sessionId = 0L
    private var shutdownFuture: ScheduledFuture[_] = null
    private val shutdownHandle = makeRunnable {
        connectionSubscriber.synchronized {
            if (isRunning) {
                log error "Backend connection is lost after being disconnected " +
                          s"for ${config.graceTime} milliseconds: shutting " +
                          "down"
                shutdown(MidonetBackend.NsdbErrorCodeGraceTimeExpired)
            }
        }
    }

    private val zoom =
        new ZookeeperObjectMapper(config, namespaceId.toString, curator,
                                  failFastCurator, stateTableClientWrapper,
                                  reactor, new StorageMetrics(metricRegistry))

    override def store: Storage = zoom
    override def stateStore: StateStorage = zoom
    override def stateTableStore: StateTableStorage = zoom

    override def stateTableClient: StateTableClient = stateProxyClient
    override def discovery: MidonetDiscovery = discoveryService

    protected def setup(stateTableStorage: StateTableStorage): Unit = { }

    protected override def doStart(): Unit = {
        log.info("Starting backend store for host {}", namespaceId)
        try {
            if (curator.getState != CuratorFrameworkState.STARTED) {
                curator.start()
            }
            if (failFastCurator.getState != CuratorFrameworkState.STARTED) {
                failFastCurator.start()
            }

            discoveryServiceExecutor = Executors.singleThreadScheduledExecutor(
                "discovery-service", isDaemon = true, Executors.CallerRunsPolicy)
            discoveryService = new MidonetDiscoveryImpl(curator,
                                                        discoveryServiceExecutor,
                                                        config)

            if (config.stateClient.enabled) {
                stateProxyClientExecutor = Executors
                    .singleThreadScheduledExecutor(
                        StateProxyService.Name,
                        isDaemon = true,
                        Executors.CallerRunsPolicy)
                val ec = ExecutionContext.fromExecutor(stateProxyClientExecutor)
                val numNettyThreads = config.stateClient.numNetworkThreads
                val eventLoopGroup = new NioEventLoopGroup(numNettyThreads)

                stateProxyClient = new StateProxyClient(
                    config.stateClient,
                    discoveryService,
                    stateProxyClientExecutor,
                    eventLoopGroup)(ec)

                stateProxyClient.start()
            }

            log.info("Setting up storage bindings")
            MidonetBackend.setupBindings(zoom, zoom, () => {
                setup(zoom)
                if (reflections.isDefined) {
                    setupFromClasspath(zoom, zoom, reflections.get)
                }
            })
            zoom.enableLock()

            log.info("Start observing backend connection")
            connectionState subscribe connectionSubscriber

            notifyStarted()
        } catch {
            case NonFatal(e) =>
                log.error("Failed to start backend service", e)
                this.notifyFailed(e)
        }
    }

    protected def doStop(): Unit = {
        log.info("Stopping backend store for host {}", namespaceId)

        connectionSubscriber.synchronized {
            if (shutdownFuture ne null) {
                shutdownFuture.cancel(true)
                shutdownFuture = null
            }
        }

        connectionSubscriber.unsubscribe()
        reactor.shutDownNow()

        curator.close()
        failFastCurator.close()

        if (config.stateClient.enabled) {
            stateProxyClient.stop()
            stateProxyClientExecutor.shutdown()
        }

        discoveryService.stop()
        discoveryServiceExecutor.shutdown()

        notifyStopped()
    }

    /**
      * This method allows hooks to be inserted in the classpath, so that setup
      * code can be executed before the MidoNet backend is built. Such hook
      * classes must implement ZoomInitializer and have the @ZoomInit
      * annotation.
      */
    private def setupFromClasspath(store: Storage, stateStore: StateStorage,
                                   reflections: Reflections): Unit = {
        log.info("Scanning classpath for storage plugins...")

        val initializers = reflections.getSubTypesOf(classOf[ZoomInitializer])

        initializers.filter(_.getAnnotation(classOf[ZoomInit]) != null)
            .foreach { initializer =>
                try {
                    log.info(s"Initialize storage from ${initializer.getName}")
                    val instance = initializer.getConstructors()(0)
                        .newInstance()
                        .asInstanceOf[ZoomInitializer]
                    instance.setup(store, stateStore)
                } catch {
                    case NonFatal(e) =>
                        log.warn("Failed to initialize storage from " +
                                 s"${initializer.getName}", e)
                }
            }
    }

    /**
      * Handles changes to the backend connection state, as follows:
      *
      * - When the connection is [[ConnectionState.SUSPENDED]] (aka
      * [[org.apache.zookeeper.Watcher.Event.KeeperState.Disconnected]])
      * the method will install a timer that expires after the configured
      * grace time. On timer expiration, the backend will shutdown the current
      * process.
      *
      * - When the connection is [[ConnectionState.RECONNECTED]] (aka
      * [[org.apache.zookeeper.Watcher.Event.KeeperState.SyncConnected]])
      * the method will check whether it reconnected with the same session
      * identifier, and otherwise shutdown the process.
      */
    private def connectionStateChanged(state: ConnectionState): Unit = {
        state match {
            case ConnectionState.CONNECTED =>
                sessionId = curator.getZookeeperClient.getZooKeeper.getSessionId
                log info "Backend is connected with session identifier " +
                         s"$sessionId"

            case ConnectionState.SUSPENDED =>
                log warn "Backend connection is suspended: MidoNet will " +
                         "shutdown after a grace time of " +
                         s"${config.graceTime} milliseconds if the " +
                         "session is not restored"
                if (connectionSuspendedTime < 0) {
                    connectionSuspendedTime = System.currentTimeMillis()
                }

                connectionSubscriber.synchronized {
                    if (shutdownFuture ne null) {
                        shutdownFuture.cancel(true)
                    }
                    shutdownFuture = reactor.schedule(shutdownHandle,
                                                      config.graceTime,
                                                      TimeUnit.MILLISECONDS)
                }

            case ConnectionState.RECONNECTED =>
                val currentSessionId =
                    curator.getZookeeperClient.getZooKeeper.getSessionId
                if (sessionId != currentSessionId) {
                    log error "The backend has reconnected with a different " +
                              s"session identifier old=$sessionId " +
                              s"new=$currentSessionId: shutting down"
                    shutdown(MidonetBackend.NsdbErrorCodeSessionExpired)
                }

                val suspendedTime = connectionSuspendedTime
                if (suspendedTime > 0) {
                    val duration = System.currentTimeMillis() - suspendedTime
                    connectionSuspendedTime = -1L
                    log info "Backend is reconnected after being disconnected " +
                             s"for $duration milliseconds with session " +
                             s"identifier $sessionId"
                } else {
                    log info "Backend is reconnected with session identifier " +
                             s"$sessionId"
                }

                connectionSubscriber.synchronized {
                    if (shutdownFuture ne null) {
                        shutdownFuture.cancel(true)
                        shutdownFuture = null
                    }
                }

            case ConnectionState.READ_ONLY =>
                log warn "Backend connection is read-only"

            case ConnectionState.LOST =>
                if (isRunning) {
                    log error "Backend session has expired: shutting down"
                    shutdown(MidonetBackend.NsdbErrorCodeSessionExpired)
                }
        }
    }

    /**
      * Shuts down the current process with the given exit code upon losing the
      * ZooKeeper session. The method first closes the Curator instances, since
      * they do not use daemon threads.
      */
    private def shutdown(code: Int): Unit = {
        curator.close()
        failFastCurator.close()
        System.exit(code)
    }

}
