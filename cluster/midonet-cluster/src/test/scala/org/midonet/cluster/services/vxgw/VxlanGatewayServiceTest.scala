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

import ch.qos.logback.classic.Level
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

import org.midonet.cluster._
import org.midonet.cluster.data.{Bridge, VTEP}
import org.midonet.cluster.util.CuratorTestFramework
import org.midonet.conf.MidoTestConfigurator
import org.midonet.midolman.host.state.HostZkManager
import org.midonet.midolman.state.{Directory, ZookeeperConnectionWatcher}
import org.midonet.packets.IPv4Addr

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

        // Avoid spam on some verbose midonet classes, the test will generate
        // lot of these which will impact perf. and risk hitting timeouts
        List(
            LoggerFactory.getLogger(classOf[LocalDataClientImpl]),
            LoggerFactory.getLogger(classOf[EntityIdSetMonitor[_]])
        ) foreach {
            _.asInstanceOf[ch.qos.logback.classic.Logger]
             .setLevel(Level.toLevel("INFO"))
        }
    }

    protected override def teardown(): Unit = {
        curator.delete().deletingChildrenIfNeeded().forPath("/midonet/vxgw")
    }

    private def makeBridges(n: Int): Seq[UUID]= 0 until n map { i =>
        dataClient.bridgesCreate(new Bridge())
    }

    private def vxgwService(conf: ClusterConfig): VxlanGatewayService = {
        // That null below will cause the VxlanGatewayManagers to throw NPE when
        // trying to contact the VtepController, but this has no effect on the
        // test. In practise it just means that the manager wouldn't be able
        // to push MACs to the VTEP.
        new VxlanGatewayService(new ClusterNode.Context(nodeId), dataClient,
                                zkConnWatcher, null, curator, metrics, conf)
    }

    // Test below verifies that initialization with a large number of bridges
    // doesn't saturate the rx streams dealing with initialization.

    "The VxGW service" should "initialize correctly even with many bridges" in {
        val conf = new ClusterConfig(MidoTestConfigurator.forClusters())
        val vx = vxgwService(conf)

        makeBridges(conf.vxgw.networkBufferSize)

        vx.startAsync()
        vx.awaitRunning(100, TimeUnit.SECONDS)

        eventually { vx.numNetworks shouldBe conf.vxgw.networkBufferSize }

        makeBridges(1)

        eventually { vx.numNetworks shouldBe conf.vxgw.networkBufferSize + 1 }

        vx.stopAsync()
        vx.awaitTerminated(100, TimeUnit.SECONDS)
    }

    "The VxGW service" should "detect networks bound and unbound to VTEPs" in {
        val conf = new ClusterConfig(MidoTestConfigurator.forClusters())
        val vx = vxgwService(conf)

        val bridges = makeBridges(2)

        vx.startAsync()
        vx.awaitRunning(100, TimeUnit.SECONDS)

        // The operations we'll do require that the VTEP exists in ZK
        val mgmtIp = IPv4Addr("192.168.1.1")
        val mgmtPort = 6632
        val vtep = new VTEP().setId(mgmtIp)
                             .setMgmtPort(mgmtPort)
                             .setTunnelIp(mgmtIp)
                             .setTunnelZone(UUID.randomUUID())
        dataClient.vtepCreate(vtep)

        eventually {
            vx.numNetworks shouldBe 2
            vx.numVxGWs shouldBe 0
        }

        // Simulate binding the bridge to a VTEP
        dataClient.bridgeCreateVxLanPort(bridges.head, mgmtIp, mgmtPort, 100,
                                         mgmtIp, UUID.randomUUID())
        eventually {
            vx.numNetworks shouldBe 2
            vx.numVxGWs shouldBe 1
        }

        // Unbind
        dataClient.bridgeDeleteVxLanPort(bridges.head, mgmtIp)
        eventually {
            vx.numNetworks shouldBe 2
            vx.numVxGWs shouldBe 0
        }

        // Rebind, and the VxGW should detect it
        dataClient.bridgeCreateVxLanPort(bridges.head, mgmtIp, mgmtPort, 100,
                                         mgmtIp, UUID.randomUUID())
        eventually {
            vx.numNetworks shouldBe 2
            vx.numVxGWs shouldBe 1
        }

        vx.stopAsync()
        vx.awaitTerminated(100, TimeUnit.SECONDS)
    }

    // Test below verifies that initialization with a large number of bridges
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
