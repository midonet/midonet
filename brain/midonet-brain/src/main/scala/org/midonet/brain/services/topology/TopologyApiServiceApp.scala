/*
 * Copyright 2015 Midokura SARL
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

package org.midonet.brain.services.topology

import java.util.concurrent.CountDownLatch

import com.google.common.util.concurrent.Service.{State, Listener}
import com.google.inject.{AbstractModule, Guice, Singleton}
import org.slf4j.LoggerFactory

import org.midonet.brain.{ClusterNode, ClusterNodeConfig}
import org.midonet.cluster.config.ZookeeperConfig
import org.midonet.cluster.services.MidonetBackend
import org.midonet.cluster.storage.MidonetBackendModule
import org.midonet.config.{ConfigProvider, HostIdGenerator}
import org.midonet.util.concurrent.CallingThreadExecutionContext

/** Stand-alone application to start the TopologyApiService */
object TopologyApiServiceApp extends App {
    private val log = LoggerFactory.getLogger(this.getClass)

    private val cfgFile = args(0)
    private val cfg = ConfigProvider.fromConfigFile(cfgFile)
    private val cfgProvider = ConfigProvider.providerForIniConfig(cfg)
    private val nodeCfg = cfgProvider.getConfig(classOf[ClusterNodeConfig])
    private val apiCfg = cfgProvider.getConfig(classOf[TopologyApiServiceConfig])
    private val nodeContext = new ClusterNode.Context(HostIdGenerator.getHostId(nodeCfg))

    private val topologyApiServiceModule = new AbstractModule {
        override def configure(): Unit = {
            // FIXME: Required for legacy code
            bind(classOf[ZookeeperConfig])
                .toInstance(cfgProvider.getConfig(classOf[ZookeeperConfig]))

            bind(classOf[ConfigProvider]).toInstance(cfgProvider)
            bind(classOf[TopologyApiServiceConfig]).toInstance(apiCfg)
            bind(classOf[ClusterNode.Context]).toInstance(nodeContext)
            bind(classOf[TopologyApiService]).in(classOf[Singleton])
        }
    }

    protected[brain] val injector = Guice.createInjector(
        new MidonetBackendModule(),
        topologyApiServiceModule
    )

    private val backend = injector.getInstance(classOf[MidonetBackend])
    private var srv: TopologyApiService = null
    private val srvEnded = new CountDownLatch(1)

    sys.addShutdownHook {
        if (srv != null && srv.isRunning)
            srv.stopAsync().awaitTerminated()
        if (backend.isRunning)
            backend.stopAsync().awaitTerminated()
    }

    try {
        log.info("Starting a Topology API server")
        backend.startAsync().awaitRunning()
        srv = injector.getInstance(classOf[TopologyApiService])
        srv.addListener(new Listener {
            override def terminated(from: State): Unit = srvEnded.countDown()
        }, CallingThreadExecutionContext)
        srv.startAsync().awaitRunning()
        log.info("Topology API server is up")

        srvEnded.await()

        log.info("Topology API server terminating")
    } catch {
        case e: InterruptedException =>
            log.info("Topology API server terminating")
            Thread.currentThread().interrupt()
        case e: Exception =>
            log.error("Failed to start a Topology API server", e)
    }
    System.exit(0)
}
