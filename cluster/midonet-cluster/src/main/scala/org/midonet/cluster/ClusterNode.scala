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
import javax.sql.DataSource

import scala.collection.JavaConversions._

import com.codahale.metrics.{JmxReporter, MetricRegistry}
import com.google.inject.name.Names
import com.google.inject.{AbstractModule, Guice, Singleton}
import com.typesafe.config.ConfigFactory
import org.apache.commons.dbcp2.BasicDataSource
import org.reflections.Reflections
import org.slf4j.LoggerFactory

import org.midonet.cluster.services.{ClusterService, MidonetBackend, Minion}
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

    val configurator = MidoNodeConfigurator(configFile)
    if (configurator.deployBundledConfig()) {
        log.info("Deployed new configuration schema into NSDB")
    }

    // TODO: this chunk here is required so that we override the NDSB
    //       settings and enable the new storage stack when running 2.0 code
    //       in development.  When the new storage stack is released, we should
    //       remove it.
    val CLUSTER_NODE_CONF_OVERRIDES = ConfigFactory.parseString(
        """
          |zookeeper {
          |    use_new_stack = true
          |    curator_enabled = true
          |}
        """.stripMargin)

    // Load cluster node configuration
    private val nodeId = HostIdGenerator.getHostId
    configurator.centralPerNodeConfig(nodeId)
                .mergeAndSet(CLUSTER_NODE_CONF_OVERRIDES)
    private val nodeContext = new Context(nodeId)

    val clusterConf = new ClusterConfig(configurator.runtimeConfig)

    log.info("Scanning classpath for Cluster Minions..")
    private val reflections = new Reflections("org.midonet")
    private val annotated = reflections.getTypesAnnotatedWith(classOf[ClusterService])

    /** Defines a Minion with a name, config, and implementing class */
    case class MinionDef[D <: Minion](name: String, clazz: Class[D])

    private val minions = annotated.flatMap { m=>
        val name = m.getAnnotation(classOf[ClusterService]).name()
        if (classOf[Minion].isAssignableFrom(m)) {
            log.info(s"Minion: $name provided by ${m.getName}.")
            Some(MinionDef(name, m.asInstanceOf[Class[Minion]]))
        } else {
            log.warn(s"Ignored service $name because provider class " +
                     s"${m.getName}: doesn't extend Minion.")
            None
        }
    }

    // TODO: move this out to a Guice module that provides access to the
    // NeutronDB
    private val dataSrc = new BasicDataSource()
    dataSrc.setDriverClassName(clusterConf.c3po.jdbcDriver)
    dataSrc.setUrl(clusterConf.c3po.connectionString)
    dataSrc.setUsername(clusterConf.c3po.user)
    dataSrc.setPassword(clusterConf.c3po.password)

    private val daemon = new Daemon(nodeId, minions.toList)

    private val clusterNodeModule = new AbstractModule {
        override def configure(): Unit = {

            // Common resources exposed to all Minions
            bind(classOf[MetricRegistry]).toInstance(metrics)
            bind(classOf[DataSource]).toInstance(dataSrc)
            bind(classOf[ClusterNode.Context]).toInstance(nodeContext)

            // Minion configurations
            bind(classOf[ClusterConfig]).toInstance(clusterConf)

            // Bind each Minion service as singleton, so Daemon can find them
            // and start
            minions foreach { m => bind(m.clazz).in(classOf[Singleton]) }

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
        new MidonetBackendModule(clusterConf.backend),
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

