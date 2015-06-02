/*
 * Copyright 2015 Midokura SARL
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

package org.midonet.cluster.services.vxgw

import java.util.concurrent.{CountDownLatch, TimeUnit}
import java.util.{Random, UUID}

import com.codahale.metrics.MetricRegistry
import com.google.common.util.concurrent.Service.State
import com.google.inject.{Guice, Injector}
import org.junit.Assert
import org.junit.runner.RunWith
import org.scalatest.concurrent.Eventually
import org.scalatest.junit.JUnitRunner
import org.scalatest.time.{Seconds, Span}
import org.scalatest.{BeforeAndAfter, FlatSpec, Matchers}
import org.slf4j.LoggerFactory

import org.midonet.cluster.data.Bridge
import org.midonet.cluster.util.CuratorTestFramework
import org.midonet.cluster.{ClusterConfig, ClusterNode, ClusterTestUtils, DataClient}
import org.midonet.conf.MidoTestConfigurator
import org.midonet.midolman.host.state.HostZkManager
import org.midonet.midolman.state.{Directory, ZookeeperConnectionWatcher}

@RunWith(classOf[JUnitRunner])
class VxlanGatewayServiceTest extends FlatSpec with Matchers
                                               with BeforeAndAfter
                                               with VxlanGatewayTest
                                               with CuratorTestFramework
                                               with Eventually {

    override val log = LoggerFactory.getLogger(classOf[VxlanGatewayManagerTest])

    override implicit val patienceConfig =
        PatienceConfig(timeout = scaled(Span(10, Seconds)))

    override var dataClient: DataClient = _
    override var hostManager: HostZkManager = _

    var injector: Injector = _
    var zkConnWatcher: ZookeeperConnectionWatcher = _
    var mgrClosedLatch: CountDownLatch = _
    var tzState: TunnelZoneStatePublisher = _
    var hostState: HostStatePublisher = _
    var nodeId: UUID = _
    var metrics: MetricRegistry = _

    protected override def setup(): Unit = {
        injector = Guice.createInjector(ClusterTestUtils.modules())
        val directory = injector.getInstance(classOf[Directory])
        ClusterTestUtils.setupZkTestDirectory(directory)

        dataClient = injector.getInstance(classOf[DataClient])
        Assert.assertNotNull(dataClient)

        hostState = new HostStatePublisher(dataClient, zkConnWatcher)
        tzState = new TunnelZoneStatePublisher(dataClient, zkConnWatcher,
                                               hostState, new Random)

        hostManager = injector.getInstance(classOf[HostZkManager])
        Assert.assertNotNull(hostManager)

        mgrClosedLatch = new CountDownLatch(1)
        nodeId = UUID.randomUUID

        metrics = new MetricRegistry

        curator.create().creatingParentsIfNeeded().forPath("/midonet/vxgw")
    }

    protected override def teardown(): Unit = {
        curator.delete().deletingChildrenIfNeeded().forPath("/midonet/vxgw")
    }

    private def makeBridges(n: Int): Unit = 0 until n map { i =>
        dataClient.bridgesCreate(new Bridge())
    }

    private def vxgwService(conf: ClusterConfig): VxlanGatewayService = {
        new VxlanGatewayService(new ClusterNode.Context(nodeId), dataClient,
                                zkConnWatcher, null, curator, metrics, conf)
    }

    // Test below verifids that initialization with a large number of bridges
    // doesn't saturate the rx streams dealing with initialization.

    "The VxGW service" should "initialize correctly even with many bridges" in {
        val conf = new ClusterConfig(MidoTestConfigurator.forClusters())
        val vx = vxgwService(conf)

        makeBridges(conf.vxgw.networkBufferSize )

        vx.startAsync()
        vx.awaitRunning(100, TimeUnit.SECONDS)

        eventually { vx.numNetworks shouldBe conf.vxgw.networkBufferSize }

        makeBridges(1)

        eventually { vx.numNetworks shouldBe conf.vxgw.networkBufferSize + 1 }

        vx.stopAsync()
        vx.awaitTerminated(100, TimeUnit.SECONDS)
    }

    // Test below verifids that initialization with a large number of bridges
    // over the configured buffer DOES saturate the service and alerts the
    // operator.

    "The VxGW service" should "break if the buffer is not big enough" in {
        val conf = new ClusterConfig(MidoTestConfigurator.forClusters())
        val vx = vxgwService(conf)

        makeBridges(conf.vxgw.networkBufferSize * 2) // too many bridges

        vx.startAsync()

        eventually {
            vx.state() shouldBe State.FAILED
            (vx.numNetworks <= conf.vxgw.networkBufferSize) shouldBe true
        }
    }

    "The VxGW service" should "default to 10k virtual networks" in {
        val conf = MidoTestConfigurator.forClusters()
        conf.getInt("cluster.vxgw.network_buffer_size") shouldBe 10000
    }
}
