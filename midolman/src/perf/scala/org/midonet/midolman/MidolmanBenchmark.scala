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

import scala.collection.JavaConversions._

import com.google.inject.{AbstractModule, Guice, Injector}
import com.typesafe.config.{Config, ConfigFactory}
import org.openjdk.jmh.annotations.{Setup => JmhSetup, TearDown}

import org.midonet.cluster.services.MidonetBackend
import org.midonet.cluster.storage.MidonetBackendTestModule
import org.midonet.conf.MidoTestConfigurator
import org.midonet.midolman.cluster.serialization.SerializationModule
import org.midonet.midolman.cluster.zookeeper.MockZookeeperConnectionModule
import org.midonet.midolman.cluster.LegacyClusterModule
import org.midonet.midolman.config.MidolmanConfig
import org.midonet.midolman.services.MidolmanService
import org.midonet.midolman.util._
import org.midonet.midolman.util.mock.MockMidolmanActors

trait MidolmanBenchmark extends MockMidolmanActors
                        with MidolmanServices
                        with VirtualTopologyHelper
                        with ForwardingVirtualConfigurationBuilders {
    var injector: Injector = null

    @JmhSetup
    def midolmanBenchmarkSetup(): Unit = {
        val conf = MidoTestConfigurator.forAgents(fillConfig())
        injector = Guice.createInjector(getModules(conf))
        injector = injector.createChildInjector(new MockMidolmanModule(
                hostId,
                injector,
                new MidolmanConfig(conf, ConfigFactory.empty()),
                actorsService))
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
    }

    protected def fillConfig(config: Config = ConfigFactory.empty) : Config = {
        val defaults =
            """
              |midolman.root_key = "/test/v3/midolman"
              |cassandra.servers = "localhost:9171"
            """.stripMargin
        ConfigFactory.parseString(defaults).withFallback(config)
    }

    protected def getModules(conf: Config) = {
        List(
            new SerializationModule(),
            new MidonetBackendTestModule(conf),
            new MockZookeeperConnectionModule(),
            new LegacyClusterModule(),
            new AbstractModule {
                override def configure() {
                    bind(classOf[VirtualConfigurationBuilders])
                        .to(classOf[ZoomVirtualConfigurationBuilders])
                        .asEagerSingleton()
                    }
            }
        )
    }
}
