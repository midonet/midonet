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

import scala.collection.JavaConversions._

import com.codahale.metrics.MetricRegistry
import com.google.inject.{AbstractModule, Guice, Injector, PrivateModule, Scopes}

import org.apache.commons.configuration.HierarchicalConfiguration
import org.openjdk.jmh.annotations.{TearDown, Setup => JmhSetup}

import org.midonet.cluster.Client
import org.midonet.cluster.services.StorageService
import org.midonet.config.ConfigProvider
import org.midonet.midolman.config.MidolmanConfig
import org.midonet.midolman.guice.cluster.ClusterClientModule
import org.midonet.midolman.guice.config.ConfigProviderModule
import org.midonet.midolman.guice.datapath.MockDatapathModule
import org.midonet.midolman.guice.serialization.SerializationModule
import org.midonet.midolman.guice.state.MockFlowStateStorageModule
import org.midonet.midolman.guice.zookeeper.MockZookeeperConnectionModule
import org.midonet.midolman.guice.{MidolmanActorsModule, MidolmanModule, ResourceProtectionModule, _}
import org.midonet.midolman.host.scanner.InterfaceScanner
import org.midonet.midolman.services.{DashboardService, DatapathConnectionService, HostIdProviderService, MidolmanActorsService, MidolmanService, SelectLoopService}
import org.midonet.midolman.simulation.Chain
import org.midonet.midolman.util.mock.{MockInterfaceScanner, MockMidolmanActors}
import org.midonet.midolman.util.{MidolmanServices, VirtualConfigurationBuilders, VirtualTopologyHelper}
import org.midonet.midolman.version.guice.VersionModule
import org.midonet.util.concurrent.{MockClock, NanoClock}

trait MidolmanBenchmark extends MockMidolmanActors
                        with MidolmanServices
                        with VirtualTopologyHelper
                        with VirtualConfigurationBuilders {
    var injector: Injector = null
    var clock = new MockClock

    @JmhSetup
    def midolmanBenchmarkSetup(): Unit = {
        val config = fillConfig(new HierarchicalConfiguration)
        injector = Guice.createInjector(getModules(config))
        injector.getInstance(classOf[StorageService]).startAndWait()
        injector.getInstance(classOf[MidolmanService]).startAndWait()
    }

    @TearDown
    def midolmanBenchmarkTeardown(): Unit = {
        injector.getInstance(classOf[MidolmanService]).stopAndWait()
        injector.getInstance(classOf[StorageService]).stopAndWait()
    }

    protected def fillConfig(config: HierarchicalConfiguration)
    : HierarchicalConfiguration = {
        config.setProperty("midolman.midolman_root_key", "/test/v3/midolman")
        config.setProperty("cassandra.servers", "localhost:9171")
        config
    }

    protected def getModules(config: HierarchicalConfiguration) = {
        List(
            new VersionModule(),
            new SerializationModule(),
            new ConfigProviderModule(config),
            new MockDatapathModule(),
            new StorageModule(),
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
            new ClusterClientModule(),
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
                    requireBinding(classOf[ConfigProvider])
                    requireBinding(classOf[Client])
                    requireBinding(classOf[DatapathConnectionService])
                    requireBinding(classOf[MidolmanActorsService])

                    bind(classOf[MidolmanService]).in(Scopes.SINGLETON)
                    expose(classOf[MidolmanService])

                    bind(classOf[MidolmanConfig])
                            .toProvider(classOf[MidolmanModule.MidolmanConfigProvider])
                            .in(Scopes.SINGLETON)
                    expose(classOf[MidolmanConfig])

                    bind(classOf[SelectLoopService])
                    .toInstance(new SelectLoopService {
                        override def doStart(): Unit = notifyStarted()
                        override def doStop(): Unit = notifyStopped()
                    })
                    expose(classOf[SelectLoopService])

                    bind(classOf[DashboardService])
                            .toInstance(new DashboardService {
                        override def doStart(): Unit = notifyStarted()
                        override def doStop(): Unit = notifyStopped()
                    })
                    expose(classOf[DashboardService])

                    bind(classOf[MetricRegistry])
                            .toInstance(new MetricRegistry)
                    expose(classOf[MetricRegistry])

                    requestStaticInjection(classOf[Chain])
                }
            }
        )
    }
}
