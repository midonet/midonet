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

import com.google.inject.{AbstractModule, Guice, Injector, PrivateModule}
import com.typesafe.config.{ConfigFactory, Config}
import org.openjdk.jmh.annotations.{TearDown, Setup => JmhSetup}

import org.midonet.cluster.services.{LegacyStorageService, MidonetBackend}
import org.midonet.cluster.storage.MidonetBackendTestModule
import org.midonet.conf.MidoTestConfigurator
import org.midonet.midolman.cluster.datapath.MockDatapathModule
import org.midonet.midolman.cluster.serialization.SerializationModule
import org.midonet.midolman.cluster.state.MockFlowStateStorageModule
import org.midonet.midolman.cluster.zookeeper.MockZookeeperConnectionModule
import org.midonet.midolman.cluster.{LegacyClusterModule, MidolmanActorsModule, ResourceProtectionModule}
import org.midonet.midolman.guice.config.MidolmanConfigModule
import org.midonet.midolman.host.scanner.InterfaceScanner
import org.midonet.midolman.services.{HostIdProviderService, MidolmanActorsService, MidolmanService}
import org.midonet.midolman.util.{MidolmanServices, ForwardingVirtualConfigurationBuilders, LegacyVirtualConfigurationBuilders, VirtualConfigurationBuilders, VirtualTopologyHelper}
import org.midonet.midolman.util.guice.MockMidolmanModule
import org.midonet.midolman.util.mock.{MockInterfaceScanner, MockMidolmanActors}
import org.midonet.util.concurrent.NanoClock

trait MidolmanBenchmark extends MockMidolmanActors
                        with MidolmanServices
                        with VirtualTopologyHelper
                        with ForwardingVirtualConfigurationBuilders {
    var injector: Injector = null

    @JmhSetup
    def midolmanBenchmarkSetup(): Unit = {
        injector = Guice.createInjector(getModules)
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

    protected def getModules = {
        val conf = MidoTestConfigurator.forAgents(fillConfig())
        List(
            new SerializationModule(),
            new MidolmanConfigModule(conf),
            new MockDatapathModule(),
            new MockFlowStateStorageModule(),
            new MidonetBackendTestModule(conf),
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
            new MockMidolmanModule(),
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
            new LegacyClusterModule(
                MidolmanConfigModule.createConfig(conf).kafka,
                    true /* for testing */
            ),
            new AbstractModule {
                override def configure() {
                    bind(classOf[VirtualConfigurationBuilders])
                        .to(classOf[LegacyVirtualConfigurationBuilders])
                        .asEagerSingleton()
                    }
            }
        )
    }
}
