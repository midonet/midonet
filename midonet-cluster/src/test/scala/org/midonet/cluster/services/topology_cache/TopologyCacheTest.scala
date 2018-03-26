/*
 * Copyright 2017 Midokura SARL
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

package org.midonet.cluster.services.topology_cache

import java.util.UUID

import scala.concurrent.Await
import scala.concurrent.duration._

import com.codahale.metrics.MetricRegistry

import org.apache.commons.io.FilenameUtils
import org.apache.curator.framework.CuratorFrameworkFactory
import org.apache.curator.retry.ExponentialBackoffRetry
import org.apache.curator.test.TestingServer
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest.{BeforeAndAfter, FeatureSpec, GivenWhenThen, Matchers}
import org.slf4j.LoggerFactory

import org.midonet.cluster.ClusterConfig
import org.midonet.cluster.data.ZoomMetadata.ZoomOwner
import org.midonet.cluster.models.Topology.{Network, Port}
import org.midonet.cluster.services.{MidonetBackend, MidonetBackendService}
import org.midonet.cluster.topology.TopologyBuilder
import org.midonet.cluster.util.UUIDUtil._
import org.midonet.conf.{HostIdGenerator, MidoTestConfigurator}
import org.midonet.minion.Context
import org.midonet.util.MidonetEventually
import org.midonet.util.logging.Logger

@RunWith(classOf[JUnitRunner])
class TopologyCacheTest extends FeatureSpec
                                with Matchers
                                with GivenWhenThen
                                with BeforeAndAfter
                                with MidonetEventually
                                with TopologyBuilder {

    private var id: UUID = _

    private var context: Context = _

    private var backend: MidonetBackend = _

    private var clusterConfig: ClusterConfig = _

    private var metrics: MetricRegistry = _

    private var testServer: TestingServer = _

    private val log = Logger(LoggerFactory.getLogger(this.getClass))

    before {
        testServer = new TestingServer
        log.debug(s"CONNNECT STRING: ${testServer.getConnectString}")
        val config = MidoTestConfigurator.forClusters(
            s"""
               |zookeeper.zookeeper_hosts : "${testServer.getConnectString}"
               |state_proxy.enabled : false
             """.stripMargin)
        id = UUID.randomUUID()
        context = Context(id)
        clusterConfig = new ClusterConfig(MidoTestConfigurator.forClusters(config))
        metrics = new MetricRegistry()
        val curator = CuratorFrameworkFactory.newClient(
            clusterConfig.backend.hosts,
            new ExponentialBackoffRetry(clusterConfig.backend.retryMs.toInt,
                                        clusterConfig.backend.maxRetries))
        curator.start()
        HostIdGenerator.useTemporaryHostId()
        backend = new MidonetBackendService(clusterConfig.backend, curator,
                                            curator, metrics, None)
        backend.startAsync().awaitRunning()
    }

    after {
        backend.stopAsync().awaitTerminated()
        testServer.stop()
    }

    feature("Topology Cache lifecycle") {
        scenario("Service starts and stops") {
            Given("A topology cache service")
            val cache = new TopologyCache(context,
                                          backend,
                                          clusterConfig,
                                          metrics)

            Then("The topology cache is enabled by default")
            cache.isEnabled shouldBe true

            Then("The endpoint path is an absolute path as checked by the EndpointUserRegistrar")
            FilenameUtils.getPrefix(
                FilenameUtils.normalizeNoEndSeparator(
                    cache.endpointPath, true)) should not be ""


            And("The topology cache starts")
            cache.startAsync().awaitRunning()

            And("The topology cache stops")
            cache.stopAsync().awaitTerminated()
        }

        scenario("Requesting a snapshot before cache started.") {
            Given("A topology cache service")
            val cache = new TopologyCache(context,
                                          backend,
                                          clusterConfig,
                                          metrics)

            cache.snapshotProvider shouldBe null
        }
    }

    feature("Spanshot is created without requests") {
        scenario("Non-empty storage") {
            Given("A topology cache service")
            val cache = new TopologyCache(context,
                                          backend,
                                          clusterConfig,
                                          metrics)
            cache.startAsync().awaitRunning()

            eventually {
                Then("There are no outstanding requests")
                cache.snapshotProvider.outstandingRequests shouldBe 0
                cache.snapshotProvider.outstandingRequestsDone shouldBe null
                And("The snapshot is not serialized yet")
                cache.snapshotProvider.serializedLength shouldBe 0
            }

            And("Some data in NSDB")
            backend.store.tryTransaction(ZoomOwner.None) { tx =>
                val bridge = createBridge()
                val port = createBridgePort(bridgeId = Option(bridge.getId.asJava))
                tx.create(bridge)
                tx.create(port)
            }

            eventually {
                backend.store.tryTransaction(ZoomOwner.None) { tx =>
                    tx.getAll(classOf[Port]).size shouldBe 1
                    tx.getAll(classOf[Network]).size shouldBe 1
                }
            }

            When("After some time")
            Thread.sleep(SECONDS.toMillis(TopologyCache.InitialSnapshotDelaySeconds))

            eventually {
                Then("There are no outstanding requests")
                cache.snapshotProvider.outstandingRequests shouldBe 0
                cache.snapshotProvider.outstandingRequestsDone shouldBe null
                And("The snapshot is serialized")
                cache.snapshotProvider.serializedLength should be > 0
            }

            And("Stop the cache to clear subscriptions")
            cache.stopAsync().awaitTerminated()
        }
    }

    feature("Snapshot received upon request") {
        scenario("On an empty storage") {
            Given("A topology cache service")
            val cache = new TopologyCache(context,
                                          backend,
                                          clusterConfig,
                                          metrics)
            cache.startAsync().awaitRunning()

            When("Requesting a snapshot")
            val f = cache.snapshotProvider.getAndRef()
            cache.snapshotProvider.unref()
            val snapshot = Await.result(f, 10 seconds)

            Then("The snapshot is empty")
            snapshot.capacity() shouldBe 0

            And("Stop the cache to clear subscriptions")
            cache.stopAsync().awaitTerminated()
        }

        scenario("Non-empty storage") {
            Given("A topology cache service")
            val cache = new TopologyCache(context,
                                          backend,
                                          clusterConfig,
                                          metrics)
            cache.startAsync().awaitRunning()

            And("Some data in NSDB")
            backend.store.tryTransaction(ZoomOwner.None) { tx =>
                val bridge = createBridge()
                val port = createBridgePort(bridgeId = Option(bridge.getId.asJava))
                tx.create(bridge)
                tx.create(port)
            }

            eventually {
                backend.store.tryTransaction(ZoomOwner.None) { tx =>
                    tx.getAll(classOf[Port]).size shouldBe 1
                    tx.getAll(classOf[Network]).size shouldBe 1
                }
            }

            When("Requesting a snapshot")
            eventually {
                val f = cache.snapshotProvider.getAndRef()
                cache.snapshotProvider.unref()
                val snapshot = Await.result(f, 10 seconds)

                Then("The snapshot is not empty")
                snapshot.capacity() should not be 0
            }

            And("Stop the cache to clear subscriptions")
            cache.stopAsync().awaitTerminated()
        }
    }

}
