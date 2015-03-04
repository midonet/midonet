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

import scala.collection.JavaConversions._

import com.google.inject._
import com.typesafe.config.{ConfigFactory, Config}
import org.scalatest.BeforeAndAfter
import org.scalatest.FeatureSpecLike
import org.scalatest.GivenWhenThen
import org.scalatest.Matchers
import org.scalatest.OneInstancePerTest

import org.midonet.cluster.services.{MidonetBackend, LegacyStorageService}
import org.midonet.cluster.storage.{MidonetBackendTestModule, StateStorageModule}
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
import org.midonet.midolman.util.guice.MockMidolmanModule
import org.midonet.midolman.util.mock.{MockInterfaceScanner, MockMidolmanActors}
import org.midonet.util.concurrent.{MockClock, NanoClock}

/**
 * A base trait to be used for new style Midolman simulation tests with Midolman
 * Actors.
 */
trait MidolmanSpec extends FeatureSpecLike
        with VirtualConfigurationBuilders
        with Matchers
        with BeforeAndAfter
        with GivenWhenThen
        with CustomMatchers
        with MockMidolmanActors
        with MidolmanServices
        with VirtualTopologyHelper
        with OneInstancePerTest {

    var injector: Injector = null
    var clock = new MockClock

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
              |zookeeper.cluster_storage_enabled = true
              |zookeeper.curator_enabled = false
            """.stripMargin
        config.withFallback(ConfigFactory.parseString(defaults))
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
            new StateStorageModule(),
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
            }
        )
    }
}
