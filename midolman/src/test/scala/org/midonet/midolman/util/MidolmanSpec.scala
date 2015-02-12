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
import org.apache.commons.configuration.HierarchicalConfiguration
import org.scalatest.{BeforeAndAfter, FeatureSpecLike, GivenWhenThen, Matchers, OneInstancePerTest}

import org.midonet.cluster.services.StorageService
import org.midonet.midolman.guice._
import org.midonet.midolman.guice.cluster.ClusterClientModule
import org.midonet.midolman.guice.config.ConfigProviderModule
import org.midonet.midolman.guice.datapath.MockDatapathModule
import org.midonet.midolman.guice.serialization.SerializationModule
import org.midonet.midolman.guice.state.MockFlowStateStorageModule
import org.midonet.midolman.guice.zookeeper.MockZookeeperConnectionModule
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
            val config = fillConfig(new HierarchicalConfiguration)
            injector = Guice.createInjector(getModules(config))

            injector.getInstance(classOf[StorageService])
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
        injector.getInstance(classOf[StorageService])
            .stopAsync()
            .awaitTerminated()
    }

    protected def fillConfig(config: HierarchicalConfiguration)
            : HierarchicalConfiguration = {
        config.setProperty("midolman.midolman_root_key", "/test/v3/midolman")
        config.setProperty("cassandra.servers", "localhost:9171")
        config
    }

    protected def getModules(config: HierarchicalConfiguration) = {
        List(
            new SerializationModule(),
            new ConfigProviderModule(config),
            new MockDatapathModule(),
            new MockFlowStateStorageModule(),
            new MockZookeeperConnectionModule(),
            new InMemoryStorageModule(),
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
            new ClusterClientModule(),
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
