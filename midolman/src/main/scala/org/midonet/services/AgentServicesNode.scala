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

package org.midonet.services

import java.nio.file.{Files, Paths}
import java.util.Properties
import java.util.concurrent.TimeUnit

import scala.collection.JavaConversions._
import scala.util.control.NonFatal

import com.codahale.metrics.{JmxReporter, MetricRegistry}
import com.google.inject.{AbstractModule, Guice, Singleton}
import com.typesafe.scalalogging.Logger

import org.reflections.Reflections
import org.slf4j.LoggerFactory

import org.midonet.cluster.services._
import org.midonet.cluster.storage._
import org.midonet.conf.{HostIdGenerator, LoggerLevelWatcher, MidoNodeConfigurator}
import org.midonet.midolman.config.MidolmanConfig
import org.midonet.minion.MinionService.TargetNode
import org.midonet.minion._

/** Base exception for all MidoNet Agent Minion errors. */
class AgentServicesException(msg: String, cause: Throwable)
    extends Exception(msg, cause)

/** The main application in charge of a Midonet Agent Services node. This will
  * consist of a Daemon that will spawn internal subservices ('Minions') based
  * on configuration settings to perform different distributed configuration and
  * control functions. */
object AgentServicesNode extends App {

    private val log = Logger(LoggerFactory.getLogger(AgentServicesLog))

    private val metrics = new MetricRegistry()
    private val jmxReporter = JmxReporter.forRegistry(metrics).build()

    def version = getClass.getPackage.getImplementationVersion
    def vendor = getClass.getPackage.getImplementationVendor

    log info "Agent Services node starting..."
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

    // Load node configuration
    private val nodeId = HostIdGenerator.getHostId
    private val nodeContext = Context(nodeId)

    val configurator = if (args.length > 0) {
        val configFile = args(0)
        log info s"Loading configuration: $configFile"
        if (!Files.isReadable(Paths.get(configFile))) {
            log error s"Configuration file is not readable $configFile"
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
                .subscribe(new LoggerLevelWatcher(Some("agent")))

    val midolmanConf = new MidolmanConfig(configurator.runtimeConfig)

    val servicesExecutor = ExecutorsModule(midolmanConf.services.executors, log)

    log info "Scanning classpath for Agent Minions..."
    private val reflections = new Reflections("org.midonet")
    private val annotated = reflections.getTypesAnnotatedWith(classOf[MinionService])

    private val minions = annotated.flatMap { m =>
        val annotated = m.getAnnotation(classOf[MinionService])
        val name = annotated.name()
        val node = annotated.runsOn()
        if (classOf[Minion].isAssignableFrom(m) && node == TargetNode.AGENT) {
            log info s"Minion: $name provided by ${m.getName}."
            Some(MinionDef(name, m.asInstanceOf[Class[Minion]]))
        } else {
            log warn s"Ignored service $name because provider class " +
                     s"${m.getName} is not an Agent Minion."
            None
        }
    }

    private val servicesNodeModule = new AbstractModule {
        override def configure(): Unit = {

            // Common resources exposed to all Minions
            bind(classOf[MetricRegistry]).toInstance(metrics)
            bind(classOf[Context]).toInstance(nodeContext)
            bind(classOf[Reflections]).toInstance(reflections)
            install(new ExecutorsModule(servicesExecutor,
                                        midolmanConf.services.executors))

            // Minion configurations
            bind(classOf[MidolmanConfig]).toInstance(midolmanConf)

            // Bind each Minion service as singleton, so Daemon can find them
            // and start
            minions foreach { m => bind(m.clazz).in(classOf[Singleton]) }
        }
    }

    protected var injector = Guice.createInjector(
        new MidonetBackendModule(midolmanConf.zookeeper, Some(reflections),
                                 metrics),
        servicesNodeModule
    )

    private val daemon = new Daemon(nodeId, servicesExecutor, minions.toList, injector)

    log debug "Registering shutdown hook"
    sys addShutdownHook {
        if (daemon.isRunning) {
            log info "Shutdown hook triggered, shutting down.."
            jmxReporter.stop()
            daemon.stopAsync().awaitTerminated()
        }
        if (injector.getInstance(classOf[MidonetBackend]).isRunning) {
            injector.getInstance(classOf[MidonetBackend])
                .stopAsync().awaitTerminated()
        }

        servicesExecutor.shutdown()
        if (!servicesExecutor.awaitTermination(
                midolmanConf.services.executors.threadPoolShutdownTimeoutMs,
                TimeUnit.MILLISECONDS)) {
            log warn "Shutting down the cluster executor timed out"
            servicesExecutor.shutdownNow()
        }
    }

    log info "MidoNet Agent services daemon starts..."
    try {
        jmxReporter.start()
        injector.getInstance(classOf[MidonetBackend])
                .startAsync().awaitRunning()
        daemon.startAsync().awaitRunning()
        log info "MidoNet Agent services are up!"
    } catch {
        case e: Throwable =>
            e.getCause match {
                case e: AgentServicesException =>
                    log error("The Daemon was not able to start", e.getCause)
                    System.exit(255)
                case NonFatal(t) =>
                    log error("Failure in Agent services startup", e)
                    System.exit(255)
            }
    }

}

