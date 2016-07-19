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

import scala.collection.JavaConverters._

import com.google.inject._
import com.typesafe.config.{Config, ConfigFactory, ConfigValueFactory}

import org.scalatest.{BeforeAndAfter, FeatureSpecLike, GivenWhenThen}
import org.scalatest.{Informer, Matchers, OneInstancePerTest}
import org.slf4j.LoggerFactory

import org.midonet.cluster.data.storage.InMemoryStorage
import org.midonet.cluster.services.MidonetBackend
import org.midonet.cluster.storage.MidonetBackendTestModule
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
import org.midonet.midolman.state.Directory
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
    System.setProperty("java.nio.channels.spi.SelectorProvider",
                       classOf[MockSelectorProvider].getName)

    override val info = new Informer() {
        override def apply(message: String, payload: Option[Any] = None): Unit = {
            log.info(message)
        }
    }

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

            val dir = injector.getInstance(classOf[Directory])
            ensurePath(dir, "/midonet/routers")
            ensurePath(dir, "/midonet/bridges")
            injector.getInstance(classOf[MidonetBackend])
                .startAsync()
                .awaitRunning()
            injector.getInstance(classOf[MidolmanService])
                .startAsync()
                .awaitRunning()

            InMemoryStorage.namespaceId =
                injector.getInstance(classOf[HostIdProviderService]).hostId

            simBackChannel // to ensure the processor is registered

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
    }

    private def ensurePath(dir: Directory, path: String): Unit =
        path.split("/").reduceLeft(createSegment(dir))

    private def createSegment(dir: Directory)(base: String, segment: String): String = {
        val path = base + "/" + segment
        dir.ensureHas(path, null)
        path
    }

    protected def fillConfig(config: Config = ConfigFactory.empty) : Config = {
        val defaults = """cassandra.servers = "localhost:9171""""

        config.withFallback(ConfigFactory.parseString(defaults))
              .withValue("zookeeper.use_new_stack",
                       ConfigValueFactory.fromAnyRef(true))
    }

    protected def getModules = {
        val conf = MidoTestConfigurator.forAgents(fillConfig())
        List (
            new SerializationModule(),
            new MidolmanConfigModule(conf),
            new MetricsModule(),
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
            },
            new AbstractModule {
                override def configure() {
                    bind(classOf[VirtualConfigurationBuilders])
                        .to(classOf[ZoomVirtualConfigurationBuilders])
                        .asEagerSingleton()
                }
            },
            new LegacyClusterModule()
        ).asJava
    }
}
