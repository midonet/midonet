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

import java.util.concurrent.{ExecutorService, ScheduledExecutorService}

import scala.collection.JavaConversions._
import scala.concurrent.ExecutionContext
import scala.util.control.NonFatal

import com.codahale.metrics.MetricRegistry

import io.netty.channel.nio.NioEventLoopGroup

import org.apache.curator.framework.CuratorFramework
import org.apache.curator.framework.imps.CuratorFrameworkState
import org.reflections.Reflections
import org.slf4j.LoggerFactory.getLogger

import rx.Observable

import org.midonet.cluster.backend.zookeeper.ZookeeperConnectionWatcher
import org.midonet.cluster.data.storage._
import org.midonet.cluster.data.{ZoomInit, ZoomInitializer}
import org.midonet.cluster.rpc.State.ProxyResponse.Notify
import org.midonet.cluster.services.discovery.{MidonetDiscovery, MidonetDiscoveryImpl}
import org.midonet.cluster.services.state.StateProxyService
import org.midonet.cluster.services.state.client.StateTableClient.ConnectionState.{ConnectionState => StateClientConnectionState}
import org.midonet.cluster.services.state.client.{StateProxyClient, StateSubscriptionKey, StateTableClient}
import org.midonet.cluster.storage.{CuratorZkConnection, MidonetBackendConfig}
import org.midonet.cluster.util.ConnectionObservable
import org.midonet.conf.HostIdGenerator
import org.midonet.util.concurrent.Executors
import org.midonet.util.eventloop.TryCatchReactor

/** Class responsible for providing services to access to the new Storage
  * services. */
class MidonetBackendService(config: MidonetBackendConfig,
                            override val curator: CuratorFramework,
                            override val failFastCurator: CuratorFramework,
                            metricRegistry: MetricRegistry,
                            reflections: Option[Reflections])
    extends MidonetBackend {

    private val log = getLogger("org.midonet.nsdb")

    private val namespaceId =
        if (MidonetBackend.isCluster) MidonetBackend.ClusterNamespaceId
        else HostIdGenerator.getHostId

    private var discoveryServiceExecutor: ExecutorService = null
    private var discoveryService: MidonetDiscovery = null
    private var stateProxyClientExecutor : ScheduledExecutorService = null
    @volatile private var stateProxyClient: StateProxyClient = null

    override val reactor = new TryCatchReactor("nsdb", 1)
    override val connection = new CuratorZkConnection(curator, reactor)
    override val connectionWatcher =
        ZookeeperConnectionWatcher.createWith(config, reactor, connection)

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

    private val zoom =
        new ZookeeperObjectMapper(config.rootKey, namespaceId.toString, curator,
                                  failFastCurator, stateTableClientWrapper,
                                  reactor, metricRegistry)

    override def store: Storage = zoom
    override def stateStore: StateStorage = zoom
    override def stateTableStore: StateTableStorage = zoom

    override def stateTableClient: StateTableClient = stateProxyClient

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

            notifyStarted()
            log.info("Setting up storage bindings")
            MidonetBackend.setupBindings(zoom, zoom, () => {
                setup(zoom)
                if (reflections.isDefined) {
                    setupFromClasspath(zoom, zoom, reflections.get)
                }
            })
        } catch {
            case NonFatal(e) =>
                log.error("Failed to start backend service", e)
                this.notifyFailed(e)
        }
    }

    protected def doStop(): Unit = {
        log.info("Stopping backend store for host {}", namespaceId)
        reactor.shutDownNow()
        curator.close()
        failFastCurator.close()
        notifyStopped()
        if (config.stateClient.enabled) {
            stateProxyClient.stop()
            stateProxyClientExecutor.shutdown()
        }
        discoveryService.stop()
        discoveryServiceExecutor.shutdown()
    }

    /** This method allows hooks to be inserted in the classpath, so that setup
      * code can be executed before the MidoNet backend is built. Such hook
      * classes must implement ZoomInitializer and have the @ZoomInit
      * annotation. */
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
}
