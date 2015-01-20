/*
 * Copyright 2014 Midokura SARL
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

import com.google.inject.{AbstractModule, Guice}
import org.midonet.brain.{ClusterNode, ClusterNodeConfig}
import org.midonet.cluster.data.storage.Storage
import org.midonet.cluster.storage.{MidonetBackendModule, ZoomProvider, MidonetBackendConfig}
import org.midonet.config.{HostIdGenerator, ConfigProvider}
import org.slf4j.LoggerFactory

/**
 * Stand-alone application to generate topology changes for testing
 */
object TopologyZoomUpdaterApp extends App {
    private val log = LoggerFactory.getLogger(this.getClass)

    private val cfgFile = args(0)
    private val cfg = ConfigProvider.fromConfigFile(cfgFile)
    private val cfgProvider = ConfigProvider.providerForIniConfig(cfg)
    private val nodeCfg = cfgProvider.getConfig(classOf[ClusterNodeConfig])
    private val backendCfg = cfgProvider.getConfig(classOf[MidonetBackendConfig])
    private val updCfg = cfgProvider.getConfig(classOf[TopologyZoomUpdaterConfig])
    private val nodeContext = new ClusterNode.Context(HostIdGenerator.getHostId(nodeCfg))

    private val topologyTesterModule = new AbstractModule {
        override def configure(): Unit = {
            bind(classOf[TopologyZoomUpdaterConfig]).toInstance(updCfg)
            bind(classOf[ClusterNode.Context]).toInstance(nodeContext)
            bind(classOf[Storage]).toProvider(classOf[ZoomProvider])
                .asEagerSingleton()
            bind(classOf[TopologyZoomUpdater]).asEagerSingleton()
        }
    }
    
    protected[brain] val injector = Guice.createInjector(
        new MidonetBackendModule(backendCfg),
        topologyTesterModule
    )

    sys.addShutdownHook {
        log.info("Terminating instance of Topology Tester")
        injector.getInstance(classOf[TopologyZoomUpdater])
                .stopAsync()
                .awaitTerminated()
    }

    try {
        log.info("Starting instance of Topology Tester")
        injector.getInstance(classOf[TopologyZoomUpdater])
                .startAsync()
                .awaitRunning()
        log.info("Started instance of Topology Tester")

        try {
            while (!Thread.currentThread().isInterrupted)
                Thread.sleep(600000)
        } catch {
            case e: InterruptedException => Thread.currentThread().interrupt()
        } finally {
            log.info("Interrupted. Shutting down instance of Topology Tester")
        }

    } catch {
        case e: Exception =>
            log.error("Failed to start instance of Topology Tester", e)
    }
}
