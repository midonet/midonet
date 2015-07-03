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

package org.midonet.midolman

import java.util.UUID

import com.typesafe.config.{ConfigFactory, Config}

import scala.collection.JavaConversions._

import com.codahale.metrics.MetricRegistry
import com.google.inject.{AbstractModule, Guice, Injector, PrivateModule, Scopes}
import org.openjdk.jmh.annotations.{TearDown, Setup => JmhSetup}

import org.midonet.cluster.Client
import org.midonet.cluster.services.{LegacyStorageService, MidonetBackend}
import org.midonet.conf.MidoTestConfigurator
import org.midonet.midolman.cluster.datapath.MockDatapathModule
import org.midonet.midolman.cluster.serialization.SerializationModule
import org.midonet.midolman.cluster.state.MockFlowStateStorageModule
import org.midonet.midolman.cluster.zookeeper.MockZookeeperConnectionModule
import org.midonet.midolman.cluster.{LegacyClusterModule, MidolmanActorsModule, ResourceProtectionModule}
import org.midonet.midolman.config.MidolmanConfig
import org.midonet.midolman.guice.config.MidolmanConfigModule
import org.midonet.midolman.host.scanner.InterfaceScanner
import org.midonet.midolman.services.{DatapathConnectionService, HostIdProviderService, MidolmanActorsService, MidolmanService, SelectLoopService}
import org.midonet.midolman.simulation.Chain
import org.midonet.midolman.util.mock.{MockInterfaceScanner, MockMidolmanActors}
import org.midonet.midolman.util.{MidolmanServices, ForwardingVirtualConfigurationBuilders, LegacyVirtualConfigurationBuilders, VirtualConfigurationBuilders, VirtualTopologyHelper}
import org.midonet.util.concurrent.{MockClock, NanoClock}

trait MidolmanBenchmark extends MockMidolmanActors
                        with MidolmanServices
                        with VirtualTopologyHelper
                        with ForwardingVirtualConfigurationBuilders {
    var injector: Injector = null

    @JmhSetup
    def midolmanBenchmarkSetup(): Unit = {
        val config = fillConfig()
        injector = Guice.createInjector(getModules(config))
        injector.getInstance(classOf[LegacyStorageService])
                .startAsync().awaitRunning()
        injector.getInstance(classOf[MidonetBackend])
                .startAsync().awaitRunning()
        injector.getInstance(classOf[MidolmanService])
                .startAsync().awaitRunning()
    }

    @TearDown
    def midolmanBenchmarkTeardown(): Unit = {
        injector.getInstance(classOf[MidolmanService])
            .stopAsync().awaitTerminated()
        injector.getInstance(classOf[MidonetBackend])
                .stopAsync().awaitTerminated()
        injector.getInstance(classOf[LegacyStorageService])
            .stopAsync().awaitTerminated()
    }

    protected def fillConfig(config: Config = ConfigFactory.empty) : Config = {
        val defaults =
            """
              |midolman.root_key = "/test/v3/midolman"
              |cassandra.servers = "localhost:9171"
            """.stripMargin
        ConfigFactory.parseString(defaults).withFallback(config)
    }

    protected def getModules(config: Config) = {
        List(
            new SerializationModule(),
            new MidolmanConfigModule(config),
            new MockDatapathModule(),
            new MockFlowStateStorageModule(),
            new MockZookeeperConnectionModule(),
            new AbstractModule {
                def configure() {
                    bind(classOf[HostIdProviderService])
                            .toInstance(new HostIdProviderService() {
                        val hostId = UUID.randomUUID()
                        def getHostId: UUID = hostId
                    })
                }
            },
            new LegacyClusterModule(),
            new MidolmanActorsModule {
                override def configure() {
                    bind(classOf[MidolmanActorsService])
                            .toInstance(actorsService)
                    expose(classOf[MidolmanActorsService])
                    bind(classOf[NanoClock]).toInstance(clock)
                }
            },
            new ResourceProtectionModule(),
            new PrivateModule {
                override def configure() {
                    bind(classOf[InterfaceScanner])
                            .to(classOf[MockInterfaceScanner]).asEagerSingleton()
                    expose(classOf[InterfaceScanner])
                }
            },
            // This is MidolmanModule, but doesn't create threads via the
            // SelectLoopService.
            new PrivateModule {
                override def configure() {
                    binder.requireExplicitBindings()
                    requireBinding(classOf[Client])
                    requireBinding(classOf[DatapathConnectionService])
                    requireBinding(classOf[MidolmanActorsService])

                    bind(classOf[MidolmanService]).in(Scopes.SINGLETON)
                    expose(classOf[MidolmanService])

                    bind(classOf[VirtualConfigurationBuilders])
                        .to(classOf[LegacyVirtualConfigurationBuilders])
                        .asEagerSingleton()
                    expose(classOf[VirtualConfigurationBuilders])

                    bind(classOf[MidolmanConfig])
                            .toInstance(new MidolmanConfig(MidoTestConfigurator.forAgents))
                    expose(classOf[MidolmanConfig])

                    bind(classOf[SelectLoopService])
                    .toInstance(new SelectLoopService {
                        override def doStart(): Unit = notifyStarted()
                        override def doStop(): Unit = notifyStopped()
                    })
                    expose(classOf[SelectLoopService])

                    bind(classOf[MetricRegistry])
                            .toInstance(new MetricRegistry)
                    expose(classOf[MetricRegistry])

                    requestStaticInjection(classOf[Chain])
                }
            }
        )
    }
}
