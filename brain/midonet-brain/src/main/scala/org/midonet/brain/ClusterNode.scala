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

import com.codahale.metrics.{JmxReporter, MetricRegistry}
import com.google.inject.{AbstractModule, Guice}

import org.slf4j.LoggerFactory

import org.midonet.brain.services.StorageModule
import org.midonet.brain.services.c3po.NeutronImporterConfig
import org.midonet.brain.services.heartbeat.HeartbeatConfig
import org.midonet.config.ConfigProvider

/**
 * Base exception for all MidoNet Cluster errors.
 */
class ClusterException(msg: String, cause: Throwable)
    extends Exception(msg, cause) {}

/** The main application in charge of a Midonet Cluster node. This will consist
  * of a Daemon that will spawn internal subservices ('Minions') based on
  * configuration settings to perform different distributed configuration and
  * control functions. */
object ClusterNode extends App {

    /** Defines a Minion with a name, config, and implementing class */
    case class MinionDef[D <: ClusterMinion](name: String, cfg: MinionConfig[D])

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
    private val cfg = ConfigProvider fromConfigFile configFile
    private val cfgProvider = ConfigProvider.providerForIniConfig(cfg)

    // Load configurations for all supported Minions
    private val heartbeatCfg = cfgProvider.getConfig(classOf[HeartbeatConfig])
    private val neutronPollingCfg =
        cfgProvider.getConfig(classOf[NeutronImporterConfig])

    private val minionDefs: List[MinionDef[ClusterMinion]] =
        List (new MinionDef("heartbeat", heartbeatCfg),
              new MinionDef("neutron-importer", neutronPollingCfg))

    log.info("Initialising MidoNet Cluster..")
    // Expose the known minions to the Daemon, without starting them
    private val daemon = new Daemon(minionDefs)

    private val clusterNodeModule = new AbstractModule {
        override def configure(): Unit = {

            bind(classOf[ConfigProvider]).toInstance(cfgProvider)
            bind(classOf[MetricRegistry]).toInstance(metrics)

            bind(classOf[HeartbeatConfig]).toInstance(heartbeatCfg)
            bind(classOf[NeutronImporterConfig]).toInstance(neutronPollingCfg)
            minionDefs foreach { m =>
                log.info(s"Register minion: ${m.name}")
                install(MinionConfig.module(m.cfg))
            }

            bind(classOf[Daemon]).toInstance(daemon)
        }
    }

    protected[brain] val injector = Guice.createInjector(
        clusterNodeModule,
        new StorageModule(cfgProvider))

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
                case _: ClusterException =>
                    log error("The Daemon was not able to start", e.getCause)
                case _ =>
                    log error(".. actually, not. See error trace", e)
            }
    }
}
