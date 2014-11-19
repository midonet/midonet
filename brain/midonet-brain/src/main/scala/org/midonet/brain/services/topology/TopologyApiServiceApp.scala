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

import com.google.inject.{Inject, Provides, AbstractModule, Guice}

import org.apache.curator.framework.CuratorFramework
import org.slf4j.LoggerFactory

import org.midonet.cluster.config.ZookeeperConfig
import org.midonet.cluster.data.storage.{Storage, ZookeeperObjectMapper}
import org.midonet.config.ConfigProvider
import org.midonet.midolman.guice.zookeeper.ZookeeperConnectionModule.CuratorFrameworkProvider

/**
 * Main application to start the TopologyApiService
 */
object TopologyApiServiceApp extends App {
    private val log = LoggerFactory.getLogger(this.getClass)
    
    private val cfgFile = args(0)
    private val cfg = ConfigProvider.fromConfigFile(cfgFile)
    private val cfgProvider = ConfigProvider.providerForIniConfig(cfg)

    private val apiCfg = cfgProvider.getConfig(classOf[TopologyApiServiceConfig])
    private val zkCfg = cfgProvider.getConfig(classOf[ZookeeperConfig])

    protected[brain] val injector = Guice.createInjector(new AbstractModule {
        override def configure(): Unit = {
            bind(classOf[ConfigProvider]).toInstance(cfgProvider)
            bind(classOf[TopologyApiServiceConfig]).toInstance(apiCfg)

            bind(classOf[ZookeeperConfig]).toInstance(zkCfg)
            bind(classOf[CuratorFramework])
                .toProvider(classOf[CuratorFrameworkProvider])

            bind(classOf[Storage]).to(classOf[ZookeeperObjectMapper])
            bind(classOf[TopologyApiService]).asEagerSingleton()
        }

        @Provides
        def provideZookeeperObjectMapper(curator: CuratorFramework):
            ZookeeperObjectMapper =
            new ZookeeperObjectMapper("/zoom", curator)
    })

    sys.addShutdownHook {
        log.info("Terminating Topology API server")
        injector.getInstance(classOf[TopologyApiService])
                .stopAsync()
                .awaitTerminated()
    }

    try {
        log.info("Starting Topology API server")
        injector.getInstance(classOf[TopologyApiService])
                .startAsync()
                .awaitRunning()
        log.info("Started Topology API server")

        try {
            while (!Thread.currentThread().isInterrupted)
                Thread.sleep(600000)
        } catch {
            case e: InterruptedException => Thread.currentThread().interrupt()
        } finally {
            log.info("Interrupted. Shutting down")
        }

    } catch {
        case e: Exception =>
            log.error("Failed to start Topology API server", e)
    }
}
