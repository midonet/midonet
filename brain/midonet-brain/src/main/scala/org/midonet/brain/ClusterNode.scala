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

package org.midonet.brain

import java.nio.file.{Files, Paths}
import java.util.UUID
import javax.sql.DataSource

import com.codahale.metrics.{JmxReporter, MetricRegistry}
import com.google.inject.{AbstractModule, Guice}
import org.apache.commons.dbcp2.BasicDataSource
import org.slf4j.LoggerFactory

import org.midonet.brain.services.c3po.C3POConfig
import org.midonet.brain.services.heartbeat.HeartbeatConfig
import org.midonet.brain.services.vxgw.VxLanGatewayService.VxGWServiceConfig
import org.midonet.cluster.data.storage.Storage
import org.midonet.cluster.storage._
import org.midonet.config._

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
      * @param nodeId the UUID of this cluster node
      * @param embed whether this service is enabled as an embedded service (
      *              this is legacy for the REST API)
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
    // Load configurations for all supported Minions
    private val cfg = ConfigProvider fromConfigFile configFile
    private val cfgProvider = ConfigProvider.providerForIniConfig(cfg)

    // Load cluster node configuration
    private val nodeCfg = cfgProvider.getConfig(classOf[ClusterNodeConfig])
    private val nodeId = HostIdGenerator.getHostId(nodeCfg)

    // Load configurations for backend storages
    private val backendCfg = cfgProvider.getConfig(classOf[MidonetBackendConfig])
    private val neutronDbConfig = cfgProvider.getConfig(classOf[NeutronDbConfig])

    // Load configurations for Cluster Node supported Minions
    private val heartbeatCfg = cfgProvider.getConfig(classOf[HeartbeatConfig])
    private val c3poConfig= cfgProvider.getConfig(classOf[C3POConfig])
    private val vxgwCfg = cfgProvider.getConfig(classOf[VxGWServiceConfig])

    // Prepare the Cluster Node context for injection
    private val nodeContext = new Context(HostIdGenerator.getHostId(nodeCfg))

    private val minionDefs: List[MinionDef[ClusterMinion]] =
        List (new MinionDef("heartbeat", heartbeatCfg),
              new MinionDef("vxgw", vxgwCfg),
              new MinionDef("neutron-importer", c3poConfig))

    // TODO: move this out to a Guice module that provides access to the
    // NeutronDB
    private val dataSrc = new BasicDataSource()
    dataSrc.setDriverClassName(neutronDbConfig.jdbcDriver)
    dataSrc.setUrl(neutronDbConfig.connectionString)
    dataSrc.setUsername(neutronDbConfig.user)
    dataSrc.setPassword(neutronDbConfig.password)

    // Expose the known minions to the Daemon, without starting any of them yet
    private val daemon = new Daemon(nodeId, minionDefs)
    private val clusterNodeModule = new AbstractModule {
        override def configure(): Unit = {

            // Common resources exposed to all Minions
            bind(classOf[MetricRegistry]).toInstance(metrics)
            bind(classOf[DataSource]).toInstance(dataSrc)
            bind(classOf[ClusterNode.Context]).toInstance(nodeContext)

            //  Minion configurations
            bind(classOf[C3POConfig]).toInstance(c3poConfig)
            bind(classOf[HeartbeatConfig]).toInstance(heartbeatCfg)
            bind(classOf[Storage]).toProvider(classOf[ZoomProvider])
                                  .asEagerSingleton()

            // Minion definitions, used by the Daemon to start when appropriate
            minionDefs foreach { m =>
                log.info(s"Register minion: ${m.name}")
                install(MinionConfig.module(m.cfg))
            }

            // The Daemon itself
            bind(classOf[Daemon]).toInstance(daemon)
        }
    }

    protected[brain] var injector = Guice.createInjector(
        new MidonetBackendModule(backendCfg),
        clusterNodeModule
    )

    log info "Registering shutdown hook"
    sys addShutdownHook {
        if (daemon.isRunning) {
            log.info("Shutting down..")
            jmxReporter.stop()
            daemon.stopAsync().awaitTerminated()
        }
    }

    log info "MidoNet Cluster daemon starts.."
    try {
        jmxReporter.start()
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

@ConfigGroup("cluster-node")
trait ClusterNodeConfig extends HostIdConfig {
    @ConfigString(key = "node_uuid", defaultValue = "")
    override def getHostId: String

    @ConfigString(key = "properties_file",
                  defaultValue = "/tmp/midonet_cluster_node.properties")
    override def getHostPropertiesFilePath: String
}

