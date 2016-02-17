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

package org.midonet.cluster

import java.nio.file.{Files, Paths}
import java.util.UUID

import com.codahale.metrics.{JmxReporter, MetricRegistry}
import com.google.inject.name.Names
import com.google.inject.{AbstractModule, Guice}
import org.slf4j.LoggerFactory

import org.midonet.cluster.services.MidonetBackend
import org.midonet.cluster.storage._
import org.midonet.conf.{HostIdGenerator, MidoNodeConfigurator}
import org.midonet.midolman.cluster.LegacyClusterModule
import org.midonet.midolman.cluster.serialization.SerializationModule
import org.midonet.midolman.cluster.zookeeper.ZookeeperConnectionModule.ZookeeperReactorProvider
import org.midonet.midolman.cluster.zookeeper.{DirectoryProvider, ZkConnectionProvider}
import org.midonet.midolman.state.{Directory, ZkConnection, ZkConnectionAwareWatcher, ZookeeperConnectionWatcher}
import org.midonet.util.eventloop.Reactor

/** Base exception for all MidoNet Cluster errors. */
class ClusterException(msg: String, cause: Throwable)
    extends Exception(msg, cause) {}

/** The main application in charge of a Midonet Cluster node. This will consist
  * of a Daemon that will spawn internal subservices ('Minions') based on
  * configuration settings to perform different distributed configuration and
  * control functions. */
object ClusterNode extends App {

    /** Defines a Minion with a name, config, and implementing class */
    case class MinionDef[D <: ClusterMinion](name: String, cfg: MinionConfig[D])

    /** Encapsulates node-wide context that might be of use for minions
      *
      * @param nodeId the UUID of this Cluster node
      * @param embed whether this service is enabled as an embedded service
      *              (this is legacy for the REST API)
      */
    case class Context(nodeId: UUID, embed: Boolean = false)

    private val log = LoggerFactory.getLogger(this.getClass)

    private val metrics = new MetricRegistry()
    private val jmxReporter = JmxReporter.forRegistry(metrics).build()

    log info "Cluster node starting.." // TODO show build.properties

    private val configFile = args(0)
    log info s"Loading configuration: $configFile"
    if (!Files.isReadable(Paths.get(configFile))) {
        System.err.println("OH NO! configuration file is not readable")
        System.exit(1)
    }

    val conf = MidoNodeConfigurator(configFile).runtimeConfig(HostIdGenerator.getHostId)
    val clusterConf = new ClusterConfig(conf)
    val backendConf = new MidonetBackendConfig(conf)

    // Load cluster node configuration
    private val nodeId = HostIdGenerator.getHostId

    // Prepare the Cluster node context for injection
    private val nodeContext = new Context(nodeId)

    private val minionDefs: List[MinionDef[ClusterMinion]] =
        List (new MinionDef("heartbeat", clusterConf.hearbeat),
              new MinionDef("vxgw", clusterConf.vxgw),
              new MinionDef("topology", clusterConf.topologyApi),
              new MinionDef("flow-tracing", clusterConf.flowTracing))

    private val daemon = new Daemon(nodeId, minionDefs)
    private val clusterNodeModule = new AbstractModule {
        override def configure(): Unit = {

            // Common resources exposed to all Minions
            bind(classOf[MetricRegistry]).toInstance(metrics)
            bind(classOf[ClusterNode.Context]).toInstance(nodeContext)

            // Minion configurations
            bind(classOf[ClusterConfig]).toInstance(clusterConf)

            // Minion definitions, used by the Daemon to start when appropriate
            minionDefs foreach { m =>
                log.info(s"Register minion: ${m.name}")
                install(MinionConfig.module(m.cfg))
            }

            // The Daemon itself
            bind(classOf[Daemon]).toInstance(daemon)
        }
    }

    // Settings for services depending on the old
    // storage module (aka DataClient)
    // TODO: remove this when no services depend on DataClient anymore
    private val dataClientDependencies = new AbstractModule {
        override def configure(): Unit = {

            // Zookeeper stuff for DataClient
            // roughly equivalent to ZookeeperConnectionModule,
            // but without conflicts
            bind(classOf[ZkConnection])
                .toProvider(classOf[ZkConnectionProvider])
                .asEagerSingleton()
            bind(classOf[Directory])
                .toProvider(classOf[DirectoryProvider])
                .asEagerSingleton()
            bind(classOf[Reactor]).annotatedWith(
                Names.named(ZkConnectionProvider.DIRECTORY_REACTOR_TAG))
                .toProvider(classOf[ZookeeperReactorProvider])
                .asEagerSingleton()
            bind(classOf[ZkConnectionAwareWatcher])
                .to(classOf[ZookeeperConnectionWatcher])
                .asEagerSingleton()

            install(new SerializationModule)
            install(new LegacyClusterModule)
        }
    }

    protected[cluster] var injector = Guice.createInjector(
        new MidonetBackendModule(conf),
        clusterNodeModule,
        dataClientDependencies
    )

    log info "Registering shutdown hook"
    sys addShutdownHook {
        if (daemon.isRunning) {
            log.info("Shutting down..")
            jmxReporter.stop()
            daemon.stopAsync().awaitTerminated()
        }
        if (injector.getInstance(classOf[MidonetBackend]).isRunning)
            injector.getInstance(classOf[MidonetBackend])
                .stopAsync().awaitTerminated()
    }

    log info "MidoNet Cluster daemon starts.."
    try {
        jmxReporter.start()
        injector.getInstance(classOf[MidonetBackend])
            .startAsync().awaitRunning()
        daemon.startAsync().awaitRunning()
        log info "MidoNet Cluster is up"
    } catch {
        case e: Throwable =>
            e.getCause match {
                case e: ClusterException =>
                    log error("The Daemon was not able to start", e.getCause)
                case _ =>
                    log error(".. actually, not. See error trace", e)
            }
    }

}
