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
package org.midonet.midolman.util

import java.util.UUID

import org.slf4j.LoggerFactory

import org.midonet.cluster.services.{LegacyStorageService, MidonetBackend}
import org.midonet.cluster.state.LegacyStorage
import org.midonet.cluster.storage.MidonetBackendTestModule
import org.midonet.cluster.{Client, DataClient, ExplodingClient, ExplodingDataClient, ExplodingLegacyStorage, ExplodingZkManager}
import org.midonet.conf.MidoTestConfigurator
import org.midonet.midolman.cluster._
import org.midonet.midolman.cluster.datapath.MockDatapathModule
import org.midonet.midolman.cluster.serialization.SerializationModule
import org.midonet.midolman.cluster.state.MockFlowStateStorageModule
import org.midonet.midolman.cluster.zookeeper.MockZookeeperConnectionModule
import org.midonet.midolman.guice.config.MidolmanConfigModule
import org.midonet.midolman.host.scanner.InterfaceScanner
import org.midonet.midolman.services.{HostIdProviderService, MidolmanActorsService, MidolmanService}
import org.midonet.midolman.simulation.CustomMatchers
import org.midonet.midolman.state.{PathBuilder, ZkManager}
import org.midonet.midolman.util.guice.MockMidolmanModule
import org.midonet.midolman.util.mock.{MockInterfaceScanner, MockMidolmanActors}
import org.midonet.util.concurrent.NanoClock

/**
 * A base trait to be used for new style Midolman simulation tests with Midolman
 * Actors.
 */
trait MidolmanSpec extends FeatureSpecLike
        with ForwardingVirtualConfigurationBuilders
        with Matchers
        with BeforeAndAfter
        with GivenWhenThen
        with CustomMatchers
        with MockMidolmanActors
        with MidolmanServices
        with VirtualTopologyHelper
        with OneInstancePerTest {

    val log = LoggerFactory.getLogger(getClass)

    var injector: Injector = null

    /**
     * Override this function to perform a custom set-up needed for the test.
     */
    protected def beforeTest(): Unit = { }

    /**
     * Override this function to perform a custom shut-down operations needed
     * for the test.
     */
    protected def afterTest(): Unit = { }

    before {
        try {
            injector = Guice.createInjector(getModules)

            injector.getInstance(classOf[LegacyStorageService])
                .startAsync()
                .awaitRunning()
            injector.getInstance(classOf[MidonetBackend])
                .startAsync()
                .awaitRunning()
            injector.getInstance(classOf[MidolmanService])
                .startAsync()
                .awaitRunning()

            beforeTest()
        } catch {
            case e: Throwable => fail(e)
        }
    }

    after {
        afterTest()
        injector.getInstance(classOf[MidolmanService])
            .stopAsync()
            .awaitTerminated()
        injector.getInstance(classOf[MidonetBackend])
            .stopAsync()
            .awaitTerminated()
        injector.getInstance(classOf[LegacyStorageService])
            .stopAsync()
            .awaitTerminated()
    }

    protected def fillConfig(config: Config = ConfigFactory.empty) : Config = {
        val defaults =
            """
              |cassandra.servers = "localhost:9171"
            """.stripMargin
        config.withFallback(ConfigFactory.parseString(defaults))
    }

    protected def getModules = {
        val conf = MidoTestConfigurator.forAgents(fillConfig())
        val modules = List(
            new SerializationModule(),
            new MidolmanConfigModule(conf),
            new MockDatapathModule(),
            new MockFlowStateStorageModule(),
            new MidonetBackendTestModule(),
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
                    expose(classOf[NanoClock])
                }
            },
            new ResourceProtectionModule(),
            new PrivateModule {
                override def configure() {
                    bind(classOf[InterfaceScanner])
                            .to(classOf[MockInterfaceScanner]).asEagerSingleton()
                    expose(classOf[InterfaceScanner])
                }
            })
        if (System.getProperty("midonet.newStack") != null) {
            log.info("Using zoom storage")
            modules :+ new AbstractModule {
                override def configure() {
                    bind(classOf[Client])
                        .to(classOf[ExplodingClient])
                        .asEagerSingleton()
                    bind(classOf[DataClient])
                        .to(classOf[ExplodingDataClient])
                        .asEagerSingleton()
                    bind(classOf[LegacyStorage])
                        .to(classOf[ExplodingLegacyStorage])
                        .asEagerSingleton()
                    bind(classOf[ZkManager])
                        .to(classOf[ExplodingZkManager])
                        .asEagerSingleton()
                    bind(classOf[PathBuilder])
                        .toInstance(new PathBuilder("/ERRORING/PATH"))
                    bind(classOf[LegacyStorageService])
                        .toInstance(new LegacyStorageService(null, null, null) {
                                        protected override def doStart(): Unit = {
                                            notifyStarted()
                                        }
                                    })
                }
            }
        } else {
            log.info("Using legacy storage")
            modules ++ List(new LegacyClusterModule(),
                            new AbstractModule {
                                override def configure() {
                                    bind(classOf[VirtualConfigurationBuilders])
                                        .to(classOf[LegacyVirtualConfigurationBuilders])
                                        .asEagerSingleton()
                                }
                            })
        }
    }
}
