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

import com.google.inject.{AbstractModule, Guice, Singleton}
import org.midonet.conf.HostIdGenerator
import org.slf4j.LoggerFactory

import org.midonet.brain.{BrainConfig, ClusterNode}
import org.midonet.cluster.services.MidonetBackend
import org.midonet.cluster.storage.MidonetBackendModule

/** Stand-alone application to start the TopologyApiService */
object TopologyApiServiceApp extends App {
    private val log = LoggerFactory.getLogger(this.getClass)

    private val cfgFile = args(0)
    private val nodeContext = new ClusterNode.Context(HostIdGenerator.getHostId)

    private val topologyApiServiceModule = new AbstractModule {
        override def configure(): Unit = {
            bind(classOf[BrainConfig]).toInstance(BrainConfig(cfgFile))
            bind(classOf[ClusterNode.Context]).toInstance(nodeContext)
            bind(classOf[TopologyApiService]).in(classOf[Singleton])
        }
    }

    protected[brain] val injector = Guice.createInjector(
        new MidonetBackendModule(),
        topologyApiServiceModule
    )

    sys.addShutdownHook {
        log.info("Terminating Topology API server")
        injector.getInstance(classOf[TopologyApiService])
            .stopAsync().awaitTerminated()
        injector.getInstance(classOf[MidonetBackend])
            .stopAsync().awaitTerminated()
    }

    try {
        log.info("Starting a Topology API server")
        injector.getInstance(classOf[MidonetBackend])
            .startAsync().awaitRunning()
        injector.getInstance(classOf[TopologyApiService])
            .startAsync().awaitRunning()
        log.info("Topology API server is up")

        try {
            while (!Thread.currentThread().isInterrupted)
                Thread.sleep(600000)
        } catch {
            case e: InterruptedException => Thread.currentThread().interrupt()
        } finally {
            log.info("Interrupted. Shutting down the Topology API server")
        }

    } catch {
        case e: Exception =>
            log.error("Failed to start a Topology API server", e)
    }
}
