/*
 * Copyright 2016 Midokura SARL
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

package org.midonet.containers

import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.ThreadPoolExecutor.AbortPolicy

import scala.collection.JavaConverters._

import org.mockito.Mockito._
import org.mockito.{ArgumentCaptor, Matchers => MMatchers}
import org.scalatest.Matchers

import org.midonet.cluster.data.storage.Storage
import org.midonet.cluster.models.Commons.{UUID => PUUID}
import org.midonet.cluster.models.Topology._
import org.midonet.cluster.topology.TopologyBuilder
import org.midonet.cluster.util.UUIDUtil.asRichProtoUuid
import org.midonet.midolman.config.MidolmanConfig
import org.midonet.midolman.haproxy.HaproxyHelper
import org.midonet.midolman.l4lb.{HealthMonitor => _, _}
import org.midonet.midolman.topology.VirtualTopology
import org.midonet.midolman.util.MidolmanSpec
import org.midonet.packets.{IPv4Addr, IPv4Subnet, MAC}
import org.midonet.util.MidonetEventually
import org.midonet.util.OptionUtils._
import org.midonet.util.concurrent.{Executors, toFutureOps}

class HaProxyContainerTest extends MidolmanSpec
                                   with Matchers
                                   with MidonetEventually
                                   with TopologyBuilder {
    var mockHaProxyHelper: HaproxyHelper = _
    var container: HaProxyContainer = _
    var store: Storage = _

    val containerExecutor = Executors.singleThreadScheduledExecutor(
        "ha-proxy-container-test", isDaemon = false, new AbortPolicy)

    private val lbId = UUID.randomUUID()
    private val containerId = UUID.randomUUID()
    private val containerPortId = UUID.randomUUID()
    private val portMac = MAC.random()
    private val portSubnet = new IPv4Subnet("10.0.0.0", 30)
    private val ifaceName = "test-iface"
    private val namespaceName = HaproxyHelper.namespaceName(lbId.toString)

    private val containerPort = ContainerPort(
        containerPortId, UUID.randomUUID(), ifaceName,
        containerId, "haproxy", UUID.randomUUID(), lbId)

    class TestHaProxyContainer(vt: VirtualTopology,
                               containerExecutor: ExecutorService,
                               config: MidolmanConfig)
        extends HaProxyContainer(UUID.randomUUID(), vt,
                                 containerExecutor, config) {
        override protected val haProxyHelper = mockHaProxyHelper
    }

    override protected def beforeTest(): Unit = {
        mockHaProxyHelper = mock(classOf[HaproxyHelper])
        container = new TestHaProxyContainer(virtualTopology,
                                             containerExecutor, config)
        store = virtualTopology.store
    }

    feature("Invokes HaproxyHelper methods as appropriate") {
        scenario("Invokes deploy() on call to create()") {
            // Create topology with two pools, each having two members, one
            // VIP, and a health monitor.
            val tt = setupTopology(2, Seq(2, 2), Seq(1, 1), createHm = true)

            container.create(containerPort).await() shouldBe Some(namespaceName)

            verifyDeploy(tt)
        }

        scenario("Invokes restart() when topology is updated") {
            // Create a topology with one pool.
            val tt = setupTopology(1, Seq(2), Seq(1), createHm = true)
            container.create(containerPort)

            // Add a pool member.
            val newMember = createPoolMember(
                adminStateUp = true, poolId = tt.pools.head.getId.asJava,
                address = IPv4Addr.random)
            store.create(newMember)
            val updatedPool = store.get(classOf[Pool],
                                        tt.pools.head.getId).await()

            val updatedTt = TestTopology(tt.lb, Set(updatedPool),
                                         tt.router, tt.port, tt.hm)
            verifyRestart(updatedTt)
        }

        scenario("Invokes undeploy() on call to delete()") {
            val tt = setupTopology(2, Seq(2, 2), Seq(1, 1), createHm = true)
            container.create(containerPort)
            verifyDeploy(tt)

            container.delete()
            verifyUndeploy()
        }
    }

    private def verifyDeploy(tt: TestTopology): Unit = eventually {
        val lbCfgArg = ArgumentCaptor.forClass(classOf[LoadBalancerV2Config])
        verify(mockHaProxyHelper).deploy(
            lbCfgArg.capture(), MMatchers.eq(ifaceName),
            MMatchers.eq(portMac.toString),
            MMatchers.eq(containerPortAddress(portSubnet).toString),
            MMatchers.eq(routerPortAddress(portSubnet).toString))
        checkLbConfig(lbCfgArg.getValue, tt)
    }

    private def verifyRestart(tt: TestTopology): Unit = eventually {
        val lbCfgArg = ArgumentCaptor.forClass(classOf[LoadBalancerV2Config])
        verify(mockHaProxyHelper).restart(lbCfgArg.capture())
        checkLbConfig(lbCfgArg.getValue, tt)
    }

    private def verifyUndeploy(): Unit = eventually {
        verify(mockHaProxyHelper).undeploy(namespaceName, ifaceName)
    }

    private case class TestTopology(lb: LoadBalancer, pools: Set[Pool],
                                    router: Router, port: Port,
                                    hm: Option[HealthMonitor] = None)
    private def setupTopology(numPools: Int = 1, numMembers: Seq[Int] = Seq(2),
                              numVips: Seq[Int] = Seq(1),
                              createHm: Boolean = true): TestTopology = {
        val lb = createLoadBalancer(id = lbId, adminStateUp = true)
        store.create(lb)

        val hmOpt = if (createHm) {
            val hm = createHealthMonitor(adminStateUp = true)
            store.create(hm)
            Some(hm)
        } else None

        val pools = for (i <- 0 until numPools) yield {
            val pool = createPool(loadBalancerId = lbId,
                                  healthMonitorId = hmOpt.map(_.getId.asJava))
            store.create(pool)
            for (j <- 0 until numMembers(i)) {
                store.create(createPoolMember(
                    poolId = pool.getId.asJava, address = IPv4Addr.random,
                    adminStateUp = true, protocolPort = 10000 + i * 10 + j,
                    weight = 1))
            }
            for (j <- 0 until numVips(i)) {
                store.create(createVip(
                    poolId = pool.getId.asJava, adminStateUp = true,
                    address = IPv4Addr.random,
                    protocolPort = 5000 + i * 10 + j))
            }
            store.get(classOf[Pool], pool.getId).await()
        }

        val router = createRouter()
        store.create(router)

        val port = createRouterPort(
            id = containerPortId, routerId = router.getId.asJava,
            portMac = portMac, portSubnet = portSubnet)
        store.create(port)

        TestTopology(store.get(classOf[LoadBalancer], lbId).await(),
                     pools.toSet, router, port, hmOpt)
    }

    private def checkLbConfig(lbCfg: LoadBalancerV2Config,
                              tt: TestTopology): Unit = {
        lbCfg.id shouldBe tt.lb.getId.asJava

        val expectedVipIds = tt.pools.flatMap(_.getVipIdsList.asScala)
        val expectedVips = store.getAll(classOf[Vip],
                                        expectedVipIds.toSeq).await()
        val expectedListenerCfgs = expectedVips.map(toListenerConfig).toSet
        lbCfg.vips shouldBe expectedListenerCfgs

        val expectedPoolCfgs = tt.pools.map(toPoolConfig)
        lbCfg.pools shouldBe expectedPoolCfgs
    }

    private def toListenerConfig(vip: Vip): ListenerV2Config = {
        ListenerV2Config(vip.getId.asJava, vip.getAdminStateUp,
                         vip.getProtocolPort, vip.getPoolId.asJava)
    }

    private def toPoolConfig(pool: Pool): PoolV2Config = {
        PoolV2Config(
            pool.getId.asJava,
            pool.getPoolMemberIdsList.asScala.map(toPoolMemberConfig).toSet,
            toHmConfig(pool.getHealthMonitorId))
    }

    private def toPoolMemberConfig(pmId: PUUID): MemberV2Config = {
        val pm = store.get(classOf[PoolMember], pmId).await()
        MemberV2Config(pmId.asJava, pm.getAdminStateUp,
                       pm.getAddress.getAddress, pm.getProtocolPort)
    }

    private def toHmConfig(hmId: PUUID): HealthMonitorV2Config = {
        val hm = store.get(classOf[HealthMonitor], hmId).await()
        HealthMonitorV2Config(hmId.asJava, hm.getAdminStateUp,
                              hm.getDelay, hm.getTimeout, hm.getMaxRetries)
    }
}
