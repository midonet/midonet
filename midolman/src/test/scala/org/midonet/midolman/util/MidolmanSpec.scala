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
import java.util.concurrent.ConcurrentHashMap

import scala.collection.JavaConverters._

import com.google.inject._
import com.google.inject.name.Names
import com.typesafe.config.{Config, ConfigFactory}

import org.scalatest._
import org.slf4j.LoggerFactory

import org.midonet.cluster.backend.Directory
import org.midonet.cluster.data.storage.InMemoryStorage
import org.midonet.cluster.services.MidonetBackend
import org.midonet.cluster.storage.{MidonetBackendConfig, MidonetBackendTestModule}
import org.midonet.conf.MidoTestConfigurator
import org.midonet.midolman.cluster.zookeeper.MockZookeeperConnectionModule
import org.midonet.midolman.config.MidolmanConfig
import org.midonet.midolman.services.MidolmanService
import org.midonet.midolman.simulation.{CustomMatchers, PacketContext}
import org.midonet.midolman.topology.VirtualTopology
import org.midonet.midolman.util.mock.MockMidolmanActors
import org.midonet.midolman.{DatapathState, FlowTranslator, MockMidolmanModule}
import org.midonet.odp.{Flow, FlowMatch}
import org.midonet.util.collection.IPv4InvalidationArray
import org.midonet.util.eventloop.Reactor

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

    override val info = new Informer() {
        override def apply(message: String, payload: Option[Any] = None): Unit = {
            log.info(message)
        }
    }

    var injector: Injector = null

    val flowsTable = new ConcurrentHashMap[FlowMatch, Flow]

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
            flowsTable.clear()
            val conf = MidoTestConfigurator.forAgents(fillConfig())
            injector = Guice.createInjector(getModules(conf))
            injector = injector.createChildInjector(new MockMidolmanModule(
                hostId,
                flowsTable,
                injector,
                new MidolmanConfig(conf, ConfigFactory.empty()),
                injector.getInstance(classOf[MidonetBackend]),
                injector.getInstance(classOf[MidonetBackendConfig]),
                injector.getInstance(Key.get(classOf[Reactor],
                                             Names.named("directoryReactor"))),
                actorsService))

            IPv4InvalidationArray.reset()

            val dir = injector.getInstance(classOf[Directory])
            ensurePath(dir, "/midonet/routers")
            ensurePath(dir, "/midonet/bridges")
            val backend = injector.getInstance(classOf[MidonetBackend])
            backend.startAsync().awaitRunning()
            injector.getInstance(classOf[MidolmanService])
                .startAsync()
                .awaitRunning()

            InMemoryStorage.namespaceId = hostId

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
        // HTTP service is lunched at random available port in utit tests to
        // allow their parallel execution
        val defaults =
            """cassandra.servers = "localhost:9171"
              |agent.midolman.stats_http_server_port = 0
              |agent.midolman.jmx_server.enabled = false
            """.stripMargin

        config.withFallback(ConfigFactory.parseString(defaults))
    }

    protected def getModules(conf: Config) =
        List (
            new MidonetBackendTestModule(conf),
            new MockZookeeperConnectionModule(),
            new AbstractModule {
                override def configure() {
                    bind(classOf[VirtualConfigurationBuilders])
                        .to(classOf[ZoomVirtualConfigurationBuilders])
                        .asEagerSingleton()
                }
            }
        ).asJava

    sealed class TestFlowTranslator(val dpState: DatapathState) extends FlowTranslator {
        override protected val vt = injector.getInstance(classOf[VirtualTopology])
        override protected val hostId: UUID = MidolmanSpec.this.hostId
        override protected val config: MidolmanConfig = MidolmanSpec.this.config
        override protected val numWorkers: Int = 1
        override protected val workerId: Int = 0

        override def translateActions(pktCtx: PacketContext): Unit =
            super.translateActions(pktCtx)
    }
}
