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

package org.midonet.cluster

import java.lang.management.ManagementFactory
import java.nio.file.{Files, Paths}
import java.util.Properties
import java.util.concurrent.TimeUnit

import javax.sql.DataSource

import scala.collection.JavaConversions._
import scala.util.control.NonFatal

import com.codahale.metrics.{JmxReporter, MetricRegistry}
import com.google.common.base.Joiner
import com.google.inject.name.Names
import com.google.inject.{AbstractModule, Guice, Singleton}
import com.typesafe.config.ConfigRenderOptions
import com.typesafe.scalalogging.Logger

import org.apache.commons.dbcp2.BasicDataSource
import org.reflections.Reflections
import org.slf4j.LoggerFactory
import org.slf4j.bridge.SLF4JBridgeHandler

import org.midonet.cluster.auth.AuthModule
import org.midonet.cluster.backend.Directory
import org.midonet.cluster.backend.zookeeper.{ZkConnection, ZkConnectionAwareWatcher, ZkConnectionProvider, ZookeeperConnectionWatcher}
import org.midonet.cluster.services._
import org.midonet.cluster.storage._
import org.midonet.conf.{HostIdGenerator, LoggerLevelWatcher, MidoNodeConfigurator}
import org.midonet.midolman.cluster.LegacyClusterModule
import org.midonet.midolman.cluster.serialization.SerializationModule
import org.midonet.midolman.cluster.zookeeper.DirectoryProvider
import org.midonet.midolman.cluster.zookeeper.ZookeeperConnectionModule.ZookeeperReactorProvider
import org.midonet.minion.MinionService.TargetNode
import org.midonet.minion._
import org.midonet.southbound.vtep.OvsdbVtepConnectionProvider
import org.midonet.util.eventloop.Reactor

/** Base exception for all MidoNet Cluster errors. */
class ClusterException(msg: String, cause: Throwable)
    extends Exception(msg, cause) {}

/** The main application in charge of a Midonet Cluster node. This will consist
  * of a Daemon that will spawn internal subservices ('Minions') based on
  * configuration settings to perform different distributed configuration and
  * control functions. */
object ClusterNode extends App {

    private val log = Logger(LoggerFactory.getLogger(ClusterLog))

    private val metrics = new MetricRegistry()
    private val jmxReporter = JmxReporter.forRegistry(metrics).build()

    def version = getClass.getPackage.getImplementationVersion
    def vendor = getClass.getPackage.getImplementationVendor

    log info s"MidoNet Cluster $version starting..."
    log info "-------------------------------------"

    // Log version, vendor and GIT commit information.
    log info s"Version: $version"
    log info s"Vendor: $vendor"
    val gitStream = getClass.getClassLoader.getResourceAsStream("git.properties")
    if (gitStream ne null) {
        val properties = new Properties
        properties.load(gitStream)
        log info s"Branch: ${properties.get("git.branch")}"
        log info s"Commit time: ${properties.get("git.commit.time")}"
        log info s"Commit id: ${properties.get("git.commit.id")}"
        log info s"Commit user: ${properties.get("git.commit.user.name")}"
        log info s"Build time: ${properties.get("git.build.time")}"
        log info s"Build user: ${properties.get("git.build.user.name")}"
    }
    log info "-------------------------------------"

    // Log command line and JVM info.
    log info "Command-line arguments: " +
             s"${Joiner.on(' ').join(args.asInstanceOf[Array[Object]])}"
    val runtimeMxBean = ManagementFactory.getRuntimeMXBean
    val arguments = runtimeMxBean.getInputArguments
    log info "JVM options: "
    for (argument <- arguments){
        log info s"  $argument"
    }
    log info "-------------------------------------"

    // Load cluster node configuration
    private val nodeId = HostIdGenerator.getHostId
    private val nodeContext = Context(nodeId)
    MidonetBackend.isCluster = true

    // Install the SLF4J handler for the legacy loggers used in the API.
    SLF4JBridgeHandler.removeHandlersForRootLogger()
    SLF4JBridgeHandler.install()

    val configurator = if (args.length > 0) {
        val configFile = args(0)
        log info s"Loading configuration: $configFile"
        if (!Files.isReadable(Paths.get(configFile))) {
            System.err.println(s"Configuration file is not readable $configFile")
            System.exit(1)
        }
        MidoNodeConfigurator(configFile)
    } else {
        val config = MidoNodeConfigurator.bootstrapConfig()
        log info "Loading default configuration"
        MidoNodeConfigurator(config)
    }

    if (configurator.deployBundledConfig()) {
        log.info("Deployed new configuration schema into NSDB")
    }
    configurator.centralPerNodeConfig(nodeId)
    configurator.observableRuntimeConfig(nodeId)
                .subscribe(new LoggerLevelWatcher(Some("cluster")))

    val clusterConf = new ClusterConfig(configurator.runtimeConfig)
    val renderOpts = ConfigRenderOptions.defaults
        .setComments(false)
        .setOriginComments(false)
        .setFormatted(true)
        .setJson(true)
    val showConfigPasswords =
        System.getProperties.containsKey("midonet.show_config_passwords")
    log.info("Loaded configuration: {}",
             configurator.dropSchema(clusterConf.conf, showConfigPasswords)
                         .root.render(renderOpts))
    val clusterExecutor = ExecutorsModule(clusterConf.executors, log)

    log.info("Scanning classpath for Cluster Minions...")
    private val reflections = new Reflections("org.midonet")
    private val annotated = reflections.getTypesAnnotatedWith(classOf[MinionService])

    private val minions = annotated.flatMap { m =>
        val annotation = m.getAnnotation(classOf[MinionService])
        val name = annotation.name()
        val node = annotation.runsOn()
        if (classOf[Minion].isAssignableFrom(m) && node == TargetNode.CLUSTER) {
            log.info(s"Minion: $name provided by ${m.getName}.")
            Some(MinionDef(name, m.asInstanceOf[Class[Minion]]))
        } else {
            log.warn(s"Ignored service $name because provider class " +
                     s"${m.getName} is not a Cluster Minion")
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

    private val clusterNodeModule = new AbstractModule {
        override def configure(): Unit = {

            // Common resources exposed to all Minions
            bind(classOf[MetricRegistry]).toInstance(metrics)
            bind(classOf[DataSource]).toInstance(dataSrc)
            bind(classOf[Context]).toInstance(nodeContext)
            bind(classOf[Reflections]).toInstance(reflections)
            bind(classOf[LeaderLatchProvider]).in(classOf[Singleton])
            install(new AuthModule(clusterConf.auth, log))
            install(new ExecutorsModule(clusterExecutor, clusterConf.executors))

            // Minion configurations
            bind(classOf[ClusterConfig]).toInstance(clusterConf)

            // Bind each Minion service as singleton, so Daemon can find them
            // and start
            minions foreach { m => bind(m.clazz).in(classOf[Singleton]) }
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
            bind(classOf[OvsdbVtepConnectionProvider])
                .asEagerSingleton()

            install(new SerializationModule)
            install(new LegacyClusterModule)
            install(new LegacyDataClientModule)
        }
    }

    protected[cluster] var injector = Guice.createInjector(
        new MidonetBackendModule(clusterConf.backend, Some(reflections),
                                 metrics),
        clusterNodeModule,
        dataClientDependencies
    )

    private val daemon = new Daemon(nodeId, clusterExecutor, minions.toList, injector)

    log debug "Registering shutdown hook"
    sys addShutdownHook {
        log info "MidoNet Cluster shutting down..."
        if (daemon.isRunning) {
            jmxReporter.stop()
            daemon.stopAsync().awaitTerminated()
        }
        if (injector.getInstance(classOf[MidonetBackend]).isRunning)
            injector.getInstance(classOf[MidonetBackend])
                    .stopAsync().awaitTerminated()

        clusterExecutor.shutdown()
        if (!clusterExecutor.awaitTermination(
                clusterConf.executors.threadPoolShutdownTimeoutMs,
                TimeUnit.MILLISECONDS)) {
            log warn "Shutting down the cluster executor timed out"
            clusterExecutor.shutdownNow()
        }
        log info "MidoNet Cluster stopped!"
    }

    log info "MidoNet Cluster daemon starts..."
    try {
        jmxReporter.start()
        injector.getInstance(classOf[MidonetBackend])
                .startAsync().awaitRunning()
        daemon.startAsync().awaitRunning()
        log info "MidoNet Cluster started"
    } catch {
        case e: Throwable =>
            e.getCause match {
                case e: ClusterException =>
                    log error("The daemon was not able to start", e.getCause)
                    System.exit(255)
                case NonFatal(t) =>
                    log error("Failure in Cluster startup", e)
                    System.exit(255)
            }
    }

}

