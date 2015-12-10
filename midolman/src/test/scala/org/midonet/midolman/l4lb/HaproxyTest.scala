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
package org.midonet.midolman.l4lb

import java.util.UUID

import akka.actor.{ActorRef, Props}
import akka.testkit.TestActorRef
import org.apache.curator.framework.recipes.locks.InterProcessSemaphoreMutex
import org.junit.runner.RunWith

import org.midonet.cluster.ZookeeperLockFactory
import org.midonet.cluster.models.Topology.{HealthMonitor => topHM, Pool => topPool, Port, Route, Router}
import org.midonet.cluster.models.Topology.Pool.PoolHealthMonitorMappingStatus._
import org.midonet.cluster.services.MidonetBackend
import org.midonet.cluster.topology.{TopologyBuilder, TopologyMatchers}
import org.midonet.cluster.util.IPSubnetUtil
import org.midonet.cluster.util.IPSubnetUtil._
import org.midonet.cluster.util.UUIDUtil._
import org.midonet.midolman.config.MidolmanConfig
import org.midonet.midolman.l4lb.{HealthMonitor => HMSystem}
import org.midonet.midolman.simulation.RouterPort
import org.midonet.midolman.layer3
import org.midonet.midolman.layer3.Route.NextHop
import org.midonet.midolman.util.MidolmanSpec
import org.midonet.packets.{IPv4Subnet, IPv4Addr}
import org.midonet.util.MidonetEventually
import org.mockito.Mockito
import org.mockito.Mockito.{mock, times, verify}
import org.scalatest._
import org.scalatest.junit.JUnitRunner
import org.scalatest.time.SpanSugar

@RunWith(classOf[JUnitRunner])
class HaproxyTest extends MidolmanSpec
                          with TopologyBuilder
                          with TopologyMatchers
                          with ShouldMatchers
                          with SpanSugar
                          with MidonetEventually {

    var hmSystem: ActorRef = _
    var backend: MidonetBackend = _
    var conf = mock(classOf[MidolmanConfig])
    var lockFactory: ZookeeperLockFactory = _
    var poolConfig: PoolConfig = _

    val vipIp = "10.0.0.1"

    def makeRouter(): UUID = {
        val rid = UUID.randomUUID()
        val r = createRouter(id = rid,
                             adminStateUp = true)
        backend.store.create(r)
        rid
    }

    def makeLB(routerId: UUID): UUID = {
        val lbId = UUID.randomUUID()
        val lb = createLoadBalancer(id = lbId,
                                    adminStateUp = Some(true),
                                    routerId = Some(routerId))
        backend.store.create(lb)
        lbId
    }

    def makeHM(delay: Int, timeout: Int, retries: Int): UUID = {
        val hmId = UUID.randomUUID()
        val hm = createHealthMonitor(id = hmId,
                                     adminStateUp = true,
                                     delay = Some(delay),
                                     timeout = Some(timeout),
                                     maxRetries = Some(retries))
        backend.store.create(hm)
        hmId
    }

    def makeVip(port: Int, addr: String): UUID = {
        val vipId = UUID.randomUUID()
        val v = createVip(id = vipId,
                          adminStateUp = Some(true),
                          address = Some(IPv4Addr.fromString(addr)),
                          protocolPort = Some(port))
        backend.store.create(v)
        vipId
    }

    def makePool(hmId: UUID, lbId: UUID, vipId: UUID): UUID = {
        val poolId = UUID.randomUUID()
        val p = createPool(id = poolId,
                           adminStateUp = Some(true),
                           healthMonitorId = Some(hmId),
                           loadBalancerId = Some(lbId),
                           vipId = Some(vipId))
        backend.store.create(p)
        poolId
    }

    def getRouter(routerId: UUID): Router = {
        backend.store.get(classOf[Router], routerId).value.get.get
    }

    def getPort(portId: UUID): Port = {
        backend.store.get(classOf[Port], portId).value.get.get
    }

    def getRoute(routeId: UUID): Route = {
        backend.store.get(classOf[Route], routeId).value.get.get
    }

    def getPool(poolId: UUID): topPool = {
        backend.store.get(classOf[topPool], poolId).value.get.get
    }

    class TestableHealthMonitor extends HMSystem(conf, backend, lockFactory,
                                                 curator = null) {
        override def getHostId = hostId

        override def startChildHaproxyMonitor(poolId: UUID, config: PoolConfig,
                                              routerId: UUID) = {
            poolConfig = config
            context.actorOf(
                Props(
                    new HaproxyHealthMonitor(config, self, routerId, store,
                                             hostId, lockFactory) {
                        override def writeConf(config: PoolConfig): Unit = {}
                    }
                ).withDispatcher(context.props.dispatcher),
                config.id.toString)
        }
    }

    override def beforeTest(): Unit = {
        backend = injector.getInstance(classOf[MidonetBackend])
        conf = injector.getInstance(classOf[MidolmanConfig])
        lockFactory = mock(classOf[ZookeeperLockFactory])
        val mutex = mock(classOf[InterProcessSemaphoreMutex])
        Mockito.when(lockFactory.createShared(ZookeeperLockFactory.ZOOM_TOPOLOGY))
               .thenReturn(mutex)
        Mockito.when(mutex.acquire(org.mockito.Matchers.anyLong(),
                                   org.mockito.Matchers.anyObject()))
               .thenReturn(true)
        HMSystem.ipCommand = mock(classOf[IP])
        hmSystem = TestActorRef(Props(new TestableHealthMonitor()))(actorSystem)
    }

    override def afterTest(): Unit = {
        actorSystem.stop(hmSystem)
    }

    private def checkCreateNameSpace(hmName: String): Unit = {
        val dp = hmName + "_dp"
        val ns = hmName + "_ns"

        /* TODO: find out why the mapper sends 3 notifications.
         * This is not expected: a single pool-health monitor config
         * should result in a single mapper notification.
         */
        verify(HMSystem.ipCommand, times(3)).ensureNamespace(hmName)
        verify(HMSystem.ipCommand, times(3)).link("add name " + dp +
                                                  " type veth peer name " + ns)
        verify(HMSystem.ipCommand, times(3)).link("set " + dp + " up")
        verify(HMSystem.ipCommand, times(3)).link("set " + ns + " netns " +
                                                  hmName)
        verify(HMSystem.ipCommand, times(3)).execIn(hmName, "ip link set "
            + ns + " address " + HaproxyHealthMonitor.NameSpaceMAC)
        verify(HMSystem.ipCommand, times(3)).execIn(hmName, "ip link set " +
                                                              ns + " up")
        verify(HMSystem.ipCommand, times(3)).execIn(hmName, "ip address add " +
            HaproxyHealthMonitor.NameSpaceIp + "/30 dev " + ns)
        verify(HMSystem.ipCommand, times(3)).execIn(hmName,
                                                    "ip link set dev lo up")
        verify(HMSystem.ipCommand, times(3)).execIn(hmName,
            "route add default gateway " +
           HaproxyHealthMonitor.RouterIp + " " + ns)
    }

    private def checkIpTables(nsName: String, op: String, ip: String)
    : Unit = {
        val nameSpaceIp = HaproxyHealthMonitor.NameSpaceIp

        val iptablePost = "iptables --table nat --" + op + " POSTROUTING " +
                          "--source " + nameSpaceIp +
                          " --jump SNAT --to-source " + ip
        val iptablePre = "iptables --table nat --" + op +" PREROUTING" +
                         " --destination " + ip +
                         " --jump DNAT --to-destination " + nameSpaceIp
        verify(HMSystem.ipCommand, times(3)).execIn(nsName, iptablePre)
        verify(HMSystem.ipCommand, times(3)).execIn(nsName, iptablePost)
    }

    private def checkHaproxyStarted(nsName: String, vipIp: String, poolId: UUID,
                                    routerId: UUID): Unit = {

        val topoRouter = getRouter(routerId)
        topoRouter.getPortIdsCount shouldBe 1
        val portId = topoRouter.getPortIds(0).asJava

        checkCreateNameSpace(nsName)

        val router = getRouter(routerId)
        val port = getPort(portId)
        port.getRouteIdsCount shouldBe 1
        val simPort = new RouterPort(port.getId.asJava,
                                     routerId = routerId,
                                     portAddress = HaproxyHealthMonitor.RouterIp,
                                     portSubnet = HaproxyHealthMonitor.NetSubnet,
                                     portMac = HaproxyHealthMonitor.RouterMAC,
                                     hostId = hostId,
                                     interfaceName = nsName + "_dp")
        simPort shouldBeDeviceOf port

        val routeId = port.getRouteIds(0).asJava
        val route = getRoute(routeId)
        val simRoute = new layer3.Route(
            IPSubnetUtil.univSubnet4.asJava.asInstanceOf[IPv4Subnet],
            IPSubnetUtil.fromAddr(vipIp).asJava.asInstanceOf[IPv4Subnet],
            NextHop.PORT, portId, HaproxyHealthMonitor.NameSpaceIp,
            100 /* weight */, null /* routerId */)
        simRoute shouldBeDeviceOf route

        checkIpTables(nsName, "append", vipIp)
        getPool(poolId).getMappingStatus shouldBe ACTIVE
    }

    private def checkHaproxyStopped(nsName: String, poolId: UUID, routerId: UUID)
    : Unit = {
        val router = getRouter(routerId)
        router.getPortIdsCount shouldBe 0
        router.getRouteIdsCount shouldBe 0

        verify(HMSystem.ipCommand, times(3)).namespaceExist(nsName)

        // TODO: Fix what's below
        val nsDev = nsName + "_dp"
//        verify(HMSystem.ipCommand, times(3)).interfaceExistsInNs(nsName, nsDev)
//        verify(HMSystem.ipCommand, times(3)).exec("ip link delete " + nsDev)
//        verify(HMSystem.ipCommand, times(3)).deleteNS(nsName)

        // Check that ha proxy has been killed
//        val pidPath = poolConfig.haproxyPidFileLoc
//        val configPath = poolConfig.haproxyConfFileLoc
//        val pid = HealthMonitor.getHaproxyPid(pidPath)
//        HealthMonitor
//            .isRunningHaproxyPid(pid.get, pidPath, configPath) shouldBe false

        getPool(poolId).getMappingStatus shouldBe INACTIVE
    }

    feature("Single health monitor on a router") {
        scenario("router ports and routes are created and cleaned up") {
            When("We create a router")
            val routerId = makeRouter()
            var router: Router = null

            Then("The router should not have any ports nor routes")
            eventually {
                router = getRouter(routerId)
                router.getPortIdsCount shouldBe 0
                router.getRouteIdsCount shouldBe 0
            }

            When("We create a load-balancer")
            val lbId = makeLB(routerId)
            val hmId = makeHM(2, 2, 2)
            val vipId = makeVip(80, vipIp)
            val poolId = makePool(hmId, lbId, vipId)

            val hmName = poolId.toString.substring(0, 8) + "_hm"

            Then("Eventually the ha proxy should be started")
            eventually { checkHaproxyStarted(hmName, vipIp, poolId, routerId) }

            When("We delete the health monitor")
            backend.store.delete(classOf[topHM], hmId)

            Then("Eventually the ha proxy is stopped")
            eventually { checkHaproxyStopped(hmName, poolId, routerId) }
        }

        // TODO: Check HM killed if already running upon startup

        scenario("Ports and routes are created on separate routers") {
            val routerId1 = makeRouter()
            val routerId2 = makeRouter()

            val router1: Router = getRouter(routerId1)
            var router2: Router = getRouter(routerId2)
            router1.getPortIdsCount shouldBe 0
            router1.getRouteIdsCount shouldBe 0
            router2.getPortIdsCount shouldBe 0
            router2.getRouteIdsCount shouldBe 0

            val lbId1 = makeLB(routerId1)
            val hmId1 = makeHM(2, 2, 2)
            val vipId1 = makeVip(80, vipIp)
            val poolId1 = makePool(hmId1, lbId1, vipId1)
            val hmName1 = poolId1.toString.substring(0, 8) + "_hm"

            eventually {checkHaproxyStarted(hmName1, vipIp, poolId1, routerId1)}

            val portId1 = getRouter(routerId1).getPortIdsList.get(0)
            eventually { getPort(portId1.asJava).getRouteIdsCount shouldBe 1 }

            router2 = getRouter(routerId2)
            router2.getPortIdsCount shouldBe 0
            router2.getRouteIdsCount shouldBe 0

            val lbId2 = makeLB(routerId2)
            val hmId2 = makeHM(2, 2, 2)
            val vipId2 = makeVip(80, vipIp)
            val poolId2 = makePool(hmId2, lbId2, vipId2)

            val hmName2 = poolId2.toString.substring(0, 8) + "_hm"

            eventually {
                getRouter(routerId1).getRouteIdsCount shouldBe 0
                getPort(portId1.asJava).getRouteIdsCount shouldBe 1
            }

            eventually {checkHaproxyStarted(hmName2, vipIp, poolId2, routerId2)}

            // TODO:
            val portId2 = getRouter(routerId2).getPortIdsList.get(0)
            eventually { getPort(portId2.asJava).getRouteIdsCount shouldBe 1 }

            verify(HMSystem.ipCommand, times(3)).ensureNamespace(hmName1)
            verify(HMSystem.ipCommand, times(3)).ensureNamespace(hmName2)

            backend.store.delete(classOf[topPool], poolId1)
            backend.store.delete(classOf[topHM], hmId1)

            eventually { getRouter(routerId1).getPortIdsCount shouldBe 0 }
            eventually { getRouter(routerId1).getRouteIdsCount shouldBe 0 }

            eventually { getRouter(routerId2).getPortIdsCount shouldBe 1 }
            eventually { getRouter(routerId2).getRouteIdsCount shouldBe 0 }
            eventually { getPort(portId2.asJava).getRouteIdsCount shouldBe 1 }

            verify(HMSystem.ipCommand, times(3)).namespaceExist(hmName1)
            verify(HMSystem.ipCommand, times(2)).namespaceExist(hmName2)

            backend.store.delete(classOf[topPool], poolId2)
            backend.store.delete(classOf[topHM], hmId2)

            eventually { getRouter(routerId1).getPortIdsCount shouldBe 0 }
            eventually { getRouter(routerId1).getRouteIdsCount shouldBe 0 }
            eventually { getRouter(routerId2).getPortIdsCount shouldBe 0 }
            eventually { getRouter(routerId2).getRouteIdsCount shouldBe 0 }

            verify(HMSystem.ipCommand, times(3)).namespaceExist(hmName1)
            verify(HMSystem.ipCommand, times(3)).namespaceExist(hmName2)
        }
    }

    feature("Two health monitors on a router") {
        scenario("Router ports and routes are created and cleaned up") {
            val routerId = makeRouter()

            eventually { getRouter(routerId).getPortIdsCount shouldBe 0 }
            getRouter(routerId).getRouteIdsCount shouldBe 0

            val lbId = makeLB(routerId)
            val hmId1 = makeHM(2, 2, 2)
            val vipId1 = makeVip(80, vipIp)
            val poolId1 = makePool(hmId1, lbId, vipId1)

            eventually { getRouter(routerId).getPortIdsCount shouldBe 1 }
            eventually { getRouter(routerId).getPortIdsCount shouldBe 1 }
            getRouter(routerId).getRouteIdsCount shouldBe 0

            var portId1 = getRouter(routerId).getPortIdsList.get(0)
            eventually { getPort(portId1.asJava).getRouteIdsCount shouldBe 1 }

            val poolName1 = poolId1.toString.substring(0, 8) + "_hm"
            verify(HMSystem.ipCommand, times(3)).ensureNamespace(poolName1)

            val hmId2 = makeHM(2, 2, 2)
            val vipId2 = makeVip(80, "10.0.0.2")
            val poolId2 = makePool(hmId2, lbId, vipId2)

            eventually { getRouter(routerId).getPortIdsCount shouldBe 2 }
            getRouter(routerId).getRouteIdsCount shouldBe 0
            portId1 = getRouter(routerId).getPortIdsList.get(0)
            var portId2 = getRouter(routerId).getPortIdsList.get(1)
            eventually {
                getPort(portId1.asJava).getRouteIdsCount shouldBe 1
                getPort(portId2.asJava).getRouteIdsCount shouldBe 1
            }

            val poolName2 = poolId2.toString.substring(0, 8) + "_hm"
            verify(HMSystem.ipCommand, times(3)).ensureNamespace(poolName1)
            verify(HMSystem.ipCommand, times(1)).ensureNamespace(poolName2)

            backend.store.delete(classOf[topPool], poolId1)
            backend.store.delete(classOf[topHM], hmId1)

            eventually { getRouter(routerId).getPortIdsCount shouldBe 1 }
            getRouter(routerId).getRouteIdsCount shouldBe 0

            portId2 = getRouter(routerId).getPortIdsList.get(0)
            eventually { getPort(portId2.asJava).getRouteIdsCount shouldBe 1 }

            verify(HMSystem.ipCommand, times(3)).ensureNamespace(poolName1)
            verify(HMSystem.ipCommand, times(1)).ensureNamespace(poolName2)
            verify(HMSystem.ipCommand, times(3)).namespaceExist(poolName1)
            verify(HMSystem.ipCommand, times(0)).namespaceExist(poolName2)

            backend.store.delete(classOf[topPool], poolId2)
            backend.store.delete(classOf[topHM], hmId2)

            eventually{ getRouter(routerId).getPortIdsCount shouldBe 0 }
            getRouter(routerId).getRouteIdsCount shouldBe 0

            verify(HMSystem.ipCommand, times(3)).ensureNamespace(poolName1)
            verify(HMSystem.ipCommand, times(1)).ensureNamespace(poolName2)
            verify(HMSystem.ipCommand, times(3)).namespaceExist(poolName1)
            verify(HMSystem.ipCommand, times(1)).namespaceExist(poolName2)
        }
    }
}