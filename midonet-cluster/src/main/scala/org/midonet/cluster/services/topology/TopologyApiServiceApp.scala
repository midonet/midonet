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

package org.midonet.cluster.services.topology

import java.util.concurrent.CountDownLatch

import com.google.common.util.concurrent.Service.{State, Listener}
import com.google.inject.{AbstractModule, Guice, Singleton}
import org.slf4j.LoggerFactory

import org.midonet.cluster.{ClusterConfig, ClusterNode, topologyApiLog}
import org.midonet.conf.HostIdGenerator
import org.midonet.cluster.services.MidonetBackend
import org.midonet.cluster.storage.MidonetBackendModule
import org.midonet.util.concurrent.CallingThreadExecutionContext

/** Stand-alone application to start the TopologyApiService */
object TopologyApiServiceApp extends App {
    private val log = LoggerFactory.getLogger(topologyApiLog)

    private val nodeContext = new ClusterNode.Context(HostIdGenerator.getHostId)
    private val config = if (args.length > 0) {
        ClusterConfig(args(0))
    } else {
        ClusterConfig()
    }

    private val topologyApiServiceModule = new AbstractModule {
        override def configure(): Unit = {
            bind(classOf[ClusterNode.Context]).toInstance(nodeContext)
            bind(classOf[TopologyApiService]).in(classOf[Singleton])
        }
    }

    protected[cluster] val injector = Guice.createInjector(
        new MidonetBackendModule(config.backend),
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
