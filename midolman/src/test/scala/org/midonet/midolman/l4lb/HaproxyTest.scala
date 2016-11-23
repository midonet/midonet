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
package org.midonet.midolman.l4lb

import java.io.File
import java.util
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

import scala.collection.JavaConverters._
import scala.concurrent.Future

import akka.actor.{Actor, ActorRef, Props}
import akka.testkit.TestActorRef

import com.typesafe.config.{Config, ConfigFactory}

import org.apache.commons.io.FileUtils
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.mockito.Mockito.{mock, spy, times, verify}
import org.scalatest._
import org.scalatest.junit.JUnitRunner
import org.scalatest.time.SpanSugar

import org.midonet.cluster.data.storage.Storage
import org.midonet.cluster.models.Commons.LBStatus
import org.midonet.cluster.models.Topology.Pool.PoolHealthMonitorMappingStatus._
import org.midonet.cluster.models.Topology.{HealthMonitor => topHM, Pool => topPool, Vip => topVip, _}
import org.midonet.cluster.services.MidonetBackend
import org.midonet.cluster.storage.MidonetBackendConfig
import org.midonet.cluster.topology.{TopologyBuilder, TopologyMatchers}
import org.midonet.cluster.util.IPSubnetUtil._
import org.midonet.cluster.util.SequenceDispenser.SequenceType
import org.midonet.cluster.util.UUIDUtil._
import org.midonet.cluster.util.{IPAddressUtil, IPSubnetUtil, SequenceDispenser}
import org.midonet.midolman.config.MidolmanConfig
import org.midonet.midolman.l4lb.{HealthMonitor => HMSystem}
import org.midonet.midolman.layer3.Route.NextHop
import org.midonet.midolman.simulation.RouterPort
import org.midonet.midolman.util.MidolmanSpec
import org.midonet.midolman.{MockScheduler, layer3}
import org.midonet.packets.{IPSubnet, IPv4Addr, IPv4Subnet, MAC}
import org.midonet.util.MidonetEventually

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
    var poolConfig: PoolConfig = _
    var checkHealthReceived = 0
    var haProxy: TestableHaproxy = _
    val vipIp1 = "10.0.0.1"
    val vipIp2 = "10.0.0.2"

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

    def makePoolMember(poolId: UUID): UUID = {
        val member =
            createPoolMember(UUID.randomUUID(), adminStateUp = Some(true),
                            Some(poolId), Some(LBStatus.ACTIVE),
                            Some(IPv4Addr.fromString(vipIp1)),
                            protocolPort = Some(7777))
        backend.store.create(member)
        member.getId.asJava
    }

    def makePool(hmId: UUID, lbId: UUID,  vipId: UUID): UUID = {
        val p = createPool(id = UUID.randomUUID(), adminStateUp = Some(true),
                           healthMonitorId = Some(hmId),
                           loadBalancerId = Some(lbId), vipId = Some(vipId))
        backend.store.create(p)
        p.getId.asJava
    }

    def getRouter(routerId: UUID): Router = {
        backend.store.get(classOf[Router], routerId).value.get.get
    }

    def getPort(portId: UUID): Port = {
        backend.store.get(classOf[Port], portId).value.get.get
    }

    protected val backendCfg = new MidonetBackendConfig(
        ConfigFactory.parseString(
            """ zookeeper.root_key = '/'
              | state_proxy.enabled = false
            """.stripMargin))

    def getRoute(routeId: UUID): Route = {
        backend.store.get(classOf[Route], routeId).value.get.get
    }

    def getPool(poolId: UUID): topPool = {
        backend.store.get(classOf[topPool], poolId).value.get.get
    }

    def getLoadBalancer(lbId: UUID): LoadBalancer = {
        backend.store.get(classOf[LoadBalancer], lbId).value.get.get
    }

    def getVip(vipId: UUID): topVip = {
        backend.store.get(classOf[topVip], vipId).value.get.get
    }

    class TestableHaproxy(config: PoolConfig, hm: ActorRef, routerId: UUID,
                          store: Storage, hostId: UUID,
                          seqDispenser: SequenceDispenser)
        extends HaproxyHealthMonitor(config, hm, routerId, store, hostId,
                                     seqDispenser) {

            var shouldWriteConf = false

            override def writeConf(config: PoolConfig): Unit = {
                if (shouldWriteConf)
                    super.writeConf(config)
            }

            override def receive = ({
                case HaproxyHealthMonitor.CheckHealth =>
                    checkHealthReceived += 1
            }: Actor.Receive) orElse super.receive

            override def parseResponse(resp: String)
            : (Set[UUID], Set[UUID]) = {
                super.parseResponse(resp)
            }

            override def setMembersStatus(activeMembers: Set[UUID],
                                          inactiveMembers: Set[UUID]): Unit = {
                super.setMembersStatus(activeMembers, inactiveMembers)
            }
    }

    class TestableHealthMonitor extends HMSystem(conf, backend, curator = null,
                                                 backendCfg) {

        override val seqDispenser = new SequenceDispenser(null, backendCfg) {
            private val mockCounter = new AtomicInteger(100)
            def reset(): Unit = mockCounter.set(100)
            override def next(which: SequenceType): Future[Int] = {
                Future.successful(mockCounter.incrementAndGet())
            }

            override def current(which: SequenceType): Future[Int] = {
                Future.successful(mockCounter.get())
            }
        }

        override def getHostId = hostId

        override def startChildHaproxyMonitor(poolId: UUID, config: PoolConfig,
                                              routerId: UUID) = {
            poolConfig = config
            context.actorOf(
                Props({
                    haProxy = new TestableHaproxy(config, self, routerId,
                                                  store, hostId, seqDispenser)
                    haProxy
                }).withDispatcher(context.props.dispatcher),
                config.id.toString)
        }
    }

    override def fillConfig(conf: Config): Config = {
        val config = super.fillConfig(conf)
        val defaults =
           s"""
              |agent.haproxy_health_monitor.haproxy_file_loc =
              | "${FileUtils.getTempDirectoryPath}/"
            """.stripMargin

        config.withFallback(ConfigFactory.parseString(defaults))
    }

    override def beforeTest(): Unit = {
        backend = injector.getInstance(classOf[MidonetBackend])
        conf = injector.getInstance(classOf[MidolmanConfig])
        HMSystem.ipCommand = mock(classOf[IP])
        hmSystem = TestActorRef(Props(new TestableHealthMonitor()))(actorSystem)
    }

    override def afterTest(): Unit = {
        actorSystem.stop(hmSystem)
    }

    private def checkCreateNameSpace(hmName: String): Unit = {
        val dp = hmName + "_dp"
        val ns = hmName + "_ns"

        verify(HMSystem.ipCommand).ensureNamespace(hmName)
        verify(HMSystem.ipCommand).link("add name " + dp +
                                        " type veth peer name " + ns)
        verify(HMSystem.ipCommand).link("set " + dp + " up")
        verify(HMSystem.ipCommand).link("set " + ns + " netns " + hmName)
        verify(HMSystem.ipCommand).execIn(hmName, "ip link set "
            + ns + " address " + HaproxyHealthMonitor.NameSpaceMAC)
        verify(HMSystem.ipCommand).execIn(hmName, "ip link set " +
                                                  ns + " up")
        verify(HMSystem.ipCommand).execIn(hmName, "ip address add " +
            HaproxyHealthMonitor.NameSpaceIp + "/30 dev " + ns)
        verify(HMSystem.ipCommand).execIn(hmName, "ip link set dev lo up")
        verify(HMSystem.ipCommand).execIn(hmName,
            "route add default gateway " +
            HaproxyHealthMonitor.RouterIp + " " + ns)
    }

    private def checkIpTables(hmName: String, op: String, ip: String)
    : Unit = {
        val nameSpaceIp = HaproxyHealthMonitor.NameSpaceIp

        val iptablePost = "iptables --table nat --" + op + " POSTROUTING " +
                          "--source " + nameSpaceIp +
                          " --jump SNAT --to-source " + ip
        val iptablePre = "iptables --table nat --" + op +" PREROUTING" +
                         " --destination " + ip +
                         " --jump DNAT --to-destination " + nameSpaceIp
        verify(HMSystem.ipCommand).execIn(hmName, iptablePre)
        verify(HMSystem.ipCommand).execIn(hmName, iptablePost)
    }

    private def haProxyCmdLine: String = "haproxy -f " +
        poolConfig.haproxyConfFileLoc + " -p " + poolConfig.haproxyPidFileLoc

    private def checkHaproxyStarted(hmName: String, vipIp: String, poolId: UUID,
                                    routerId: UUID, routerLbCount: Int = 1)
    : Unit = {

        val topoRouter = getRouter(routerId)
        topoRouter.getPortIdsCount shouldBe routerLbCount
        verify(HMSystem.ipCommand, times(1)).execIn(hmName, haProxyCmdLine)
        checkCreateNameSpace(hmName)

        val port = topoRouter.getPortIdsList.asScala
                      .map(id => getPort(id.asJava))
                      .filter(p => p.getInterfaceName == hmName + "_dp")
                      .last
        val portId = port.getId.asJava
        port.getRouteIdsCount shouldBe 1

        val simPort = RouterPort(port.getId.asJava,
                                 tunnelKey = port.getTunnelKey,
                                 routerId = routerId,
                                 portAddress4 = HaproxyHealthMonitor.RouterIp,
                                 portAddress6 = null,
                                 portAddresses = List[IPSubnet[_]](HaproxyHealthMonitor.RouterIp).asJava,
                                 portMac = HaproxyHealthMonitor.RouterMAC,
                                 hostId = hostId,
                                 interfaceName = hmName + "_dp",
                                 fip64vxlan = config.fip64.vxlanDownlink)
        simPort shouldBeDeviceOf port

        val routeId = port.getRouteIds(0).asJava
        val route = getRoute(routeId)
        val simRoute = new layer3.Route(
            IPSubnetUtil.AnyIPv4Subnet.asJava.asInstanceOf[IPv4Subnet],
            IPSubnetUtil.fromAddress(vipIp).asJava.asInstanceOf[IPv4Subnet],
            NextHop.PORT, portId, HaproxyHealthMonitor.NameSpaceIp,
            100 /* weight */ , null /* routerId */)
        simRoute shouldBeDeviceOf route

        checkIpTables(hmName, "append", vipIp)
        getPool(poolId).getMappingStatus shouldBe ACTIVE
    }

    private def checkHaproxyStopped(hmName: String, poolId: UUID, routerId: UUID,
                                    ip: String, routerPortCount: Int = 0)
    : Unit = {
        val router = getRouter(routerId)
        router.getPortIdsCount shouldBe routerPortCount
        verify(HMSystem.ipCommand).namespaceExist(hmName)
        checkIpTables(hmName, "delete", ip)
        getPool(poolId).getMappingStatus shouldBe INACTIVE
    }

    feature("Actor messages are handled correctly") {
        scenario("ConfigUpdate") {
            Given("One router")
            val routerId = makeRouter()

            When("When we make a health monitor for the router")
            val lbId = makeLB(routerId)
            val hmId = makeHM(2, 2, 2)
            val vipId = makeVip(80, vipIp1)
            val poolId = makePool(hmId, lbId, vipId)
            val hmName = poolId.toString.substring(0, 8) + "_hm"

            Then("Eventually an HA proxy should be started")
            eventually { checkHaproxyStarted(hmName, vipIp1, poolId, routerId) }

            val portId = getRouter(routerId).getPortIds(0).asJava
            val routeId = getPort(portId).getRouteIds(0)

            eventually {
                val port = getPort(portId)
                port.getRouteIdsCount shouldBe 1
                port.getTunnelKey should be > 0L
            }

            When("We modify the VIP")
            var vip = getVip(vipId)
            vip = vip.toBuilder.setAddress(IPAddressUtil.toProto(vipIp2)).build()
            backend.store.update(vip)

            eventually {
                Then("Eventually the HA proxy is started")
                verify(HMSystem.ipCommand, times(2)).execIn(hmName, haProxyCmdLine)

                And("The previous route is deleted")
                backend.store.exists(classOf[Route], routeId)
                       .value.get.get shouldBe false

                And("The previous IP table rules are deleted")
                checkIpTables(hmName, "delete", vipIp1)

                And("A new route is created")
                val routes = backend.store.getAll(classOf[Route]).value.get.get
                routes should have size 1
                routes.last.getId.asJava should not be routeId

                And("New ip table rules are created")
                checkIpTables(hmName, "append", vipIp2)

                And("The pool status is set to active")
                getPool(poolId).getMappingStatus shouldBe ACTIVE
            }
        }

        scenario("RouterAdded/RouterRemoved") {
            Given("One router")
            val routerId = makeRouter()

            When("When we make a health monitor for the router")
            val lbId = makeLB(routerId)
            val hmId = makeHM(2, 2, 2)
            val vipId = makeVip(80, vipIp1)
            val poolId = makePool(hmId, lbId, vipId)
            val hmName = poolId.toString.substring(0, 8) + "_hm"

            Then("Eventually an HA proxy should be started")
            eventually { checkHaproxyStarted(hmName, vipIp1, poolId, routerId) }

            val portId = getRouter(routerId).getPortIds(0).asJava
            val routeId = getPort(portId).getRouteIds(0)

            When("We modify the load-balancer attached to the router")
            val routerId2 = makeRouter()
            val lb = getLoadBalancer(lbId).toBuilder
                                          .setRouterId(routerId2.asProto)
                                          .build()
            backend.store.update(lb)

            eventually {
                Then("Eventually a new router port is created")
                val ports = backend.store.getAll(classOf[Port]).value.get.get
                ports should have size 1
                ports.last.getId.asJava should not be portId
                ports.last.getRouterId.asJava shouldBe routerId2

                And("As well as a new route")
                val routes = backend.store.getAll(classOf[Route]).value.get.get
                routes should have size 1
                routes.last.getId.asJava should not be routeId

                And("The pool status is active")
                getPool(poolId).getMappingStatus shouldBe ACTIVE

                And("The previous router has no more ports attached")
                val router = getRouter(routerId)
                router.getPortIdsCount shouldBe 0
                router.getRouteIdsCount shouldBe 0
            }

            When("We delete the router")
            backend.store.update(lb.toBuilder.clearRouterId().build())

            eventually {
                Then("Eventually the router port and ip table rules are deleted")
                backend.store.getAll(classOf[Port]).value.get.get should have size 0
                checkIpTables(hmName, "delete", vipIp1)

                And("The pool status is INACTIVE")
                getPool(poolId).getMappingStatus shouldBe INACTIVE
            }
        }

        scenario("CheckHealth") {
            Given("One router")
            val routerId = makeRouter()

            When("When we make a health monitor for the router")
            val lbId = makeLB(routerId)
            val hmId = makeHM(2, 2, 2)
            val vipId = makeVip(80, vipIp1)
            val poolId = makePool(hmId, lbId, vipId)
            val hmName = poolId.toString.substring(0, 8) + "_hm"

            /* The mock scheduler used in unit tests inhibates scheduled
               messages by default, runAll ensures that all messages are
               sent. */
            actorSystem.scheduler.asInstanceOf[MockScheduler].runAll()

            Then("Eventually an HA proxy should be started")
            eventually {
                checkHaproxyStarted(hmName, vipIp1, poolId, routerId)
                checkHealthReceived should be >= 1
            }
        }
    }

    feature("HA proxies are properly started and stopped") {
        scenario("One load-balancer") {
            When("We create a router")
            val routerId = makeRouter()
            var router: Router = null

            Then("The router should not have any ports nor routes")
            router = getRouter(routerId)
            router.getPortIdsCount shouldBe 0
            router.getRouteIdsCount shouldBe 0

            When("We create a load-balancer")
            val lbId = makeLB(routerId)
            val hmId = makeHM(2, 2, 2)
            val vipId = makeVip(80, vipIp1)
            val poolId = makePool(hmId, lbId, vipId)

            val hmName = poolId.toString.substring(0, 8) + "_hm"

            Then("Eventually the ha proxy should be started")
            eventually { checkHaproxyStarted(hmName, vipIp1, poolId, routerId) }

            When("We delete the health monitor")
            backend.store.delete(classOf[topHM], hmId)

            Then("Eventually the ha proxy is stopped")
            eventually { checkHaproxyStopped(hmName, poolId, routerId, vipIp1) }
        }

        scenario("Two load-balancers on two routers") {
            When("We create two routers")
            val routerId1 = makeRouter()
            val routerId2 = makeRouter()

            Then("The routers should not have any ports nor routes")
            val router1: Router = getRouter(routerId1)
            var router2: Router = getRouter(routerId2)
            router1.getPortIdsCount shouldBe 0
            router1.getRouteIdsCount shouldBe 0
            router2.getPortIdsCount shouldBe 0
            router2.getRouteIdsCount shouldBe 0

            When("We create a load-balancer for the 1st router")
            val lbId1 = makeLB(routerId1)
            val hmId1 = makeHM(2, 2, 2)
            val vipId1 = makeVip(80, vipIp1)
            val poolId1 = makePool(hmId1, lbId1, vipId1)
            val hmName1 = poolId1.toString.substring(0, 8) + "_hm"

            Then("Eventually an HA proxy for the 1st load-balancer should be started")
            eventually { checkHaproxyStarted(hmName1, vipIp1, poolId1, routerId1) }

            And("The 2nd router should still not have any ports nor routes")
            router2 = getRouter(routerId2)
            router2.getPortIdsCount shouldBe 0
            router2.getRouteIdsCount shouldBe 0

            When("We create a load-balancer for the 2nd router")
            val lbId2 = makeLB(routerId2)
            val hmId2 = makeHM(2, 2, 2)
            val vipId2 = makeVip(80, vipIp2)
            val poolId2 = makePool(hmId2, lbId2, vipId2)

            val hmName2 = poolId2.toString.substring(0, 8) + "_hm"

            Then("Eventually a 2nd HA proxy should be started")
            eventually { checkHaproxyStarted(hmName2, vipIp2, poolId2, routerId2) }

            When("We delete the 1st health monitor")
            backend.store.delete(classOf[topHM], hmId1)

            Then("Eventually the 1st HM is stopped and the 2nd is still running")
            eventually {
                checkHaproxyStopped(hmName1, poolId1, routerId1, vipIp1)
                checkHaproxyStarted(hmName2, vipIp2, poolId2, routerId2)
            }

            When("We delete the 2nd health monitor")
            backend.store.delete(classOf[topHM], hmId2)

            Then("Eventually the 2nd HA proxy is stopped")
            eventually { checkHaproxyStopped(hmName2, poolId2, routerId2, vipIp2) }
        }

        scenario("Two load-balancers on the same router") {
            Given("One router")
            val routerId = makeRouter()

            Then("The router should not have any ports nor routes")
            getRouter(routerId).getPortIdsCount shouldBe 0
            getRouter(routerId).getRouteIdsCount shouldBe 0

            When("When we make a health monitor for the router")
            val lbId = makeLB(routerId)
            val hmId1 = makeHM(2, 2, 2)
            val vipId1 = makeVip(80, vipIp1)
            val poolId1 = makePool(hmId1, lbId, vipId1)

            val hmName1 = poolId1.toString.substring(0, 8) + "_hm"

            Then("Eventually an HA proxy should be started")
            eventually { checkHaproxyStarted(hmName1, vipIp1, poolId1, routerId) }

            When("We make a 2nd health monitor")
            val hmId2 = makeHM(2, 2, 2)
            val vipId2 = makeVip(80, "10.0.0.2")
            val poolId2 = makePool(hmId2, lbId, vipId2)

            val hmName2 = poolId2.toString.substring(0, 8) + "_hm"

            Then("Eventually a 2nd HA proxy should be started")
            eventually { checkHaproxyStarted(hmName2, vipIp2, poolId2, routerId,
                                             routerLbCount = 2) }

            When("We delete the 1st health monitor")
            backend.store.delete(classOf[topHM], hmId1)

            Then("Eventually the 1st HA proxy is stopped and the 2nd one " +
                 "is still running")
            eventually {
                checkHaproxyStopped(hmName1, poolId1, routerId, vipIp1,
                                    routerPortCount = 1)
                checkHaproxyStarted(hmName2, vipIp2, poolId2, routerId)
            }

            When("We delete the 2nd health monitor")
            backend.store.delete(classOf[topHM], hmId2)

            Then("Eventually the 2nd HA proxy is stopped")
            eventually { checkHaproxyStopped(hmName2, poolId2, routerId,
                                             vipIp2) }
        }
    }

    private def ipMockWithSetup(): IP = {
        val ip = spy(new IP())
        Mockito.doReturn(0).when(ip).exec(org.mockito.Matchers.anyString())
        Mockito.doReturn(0)
               .when(ip)
               .execNoErrors(org.mockito.Matchers.anyString())
        Mockito.doReturn(new util.LinkedList[String]())
               .when(ip)
               .execGetOutput(org.mockito.Matchers.anyString())
        ip
    }

    feature("Public methods") {
        scenario("parsing ha-proxy responses/makeChannel/writeConfig") {
            Given("One router")
            val routerId = makeRouter()

            When("When we make a health monitor for the router")
            val lbId = makeLB(routerId)
            val hmId = makeHM(2, 2, 2)
            val vipId = makeVip(80, vipIp1)
            val poolId = makePool(hmId, lbId, vipId)
            val memberId = makePoolMember(poolId)
            val hmName = poolId.toString.substring(0, 8) + "_hm"

            Then("Eventually an HA proxy should be started")
            eventually { haProxy should not be null }

            When("Parsing an ha proxy response with 1 up and 1 down node")
            val backendId1 = UUID.randomUUID()
            val backendId2 = UUID.randomUUID()
            val name1 = UUID.randomUUID()
            val name2 = UUID.randomUUID()
            var response =
                s"""
                  | $backendId1,$name1,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,UP
                  | $backendId2,$name2,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,DOWN
                """.stripMargin

            Then("The ha proxy responds correctly")
            haProxy.parseResponse(response) shouldBe (Set(name1), Set(name2))

            When("Parsing an ha proxy response with backends, frontends, and" +
                 "field names")
            val backendStr = HaproxyHealthMonitor.Backend
            val frontend = HaproxyHealthMonitor.Frontend
            val fieldName = HaproxyHealthMonitor.FieldName
            response =
                s"""
                  | ${UUID.randomUUID()},$backendStr,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,UP
                  | ${UUID.randomUUID()},$frontend,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,UP
                  | ${UUID.randomUUID()},$fieldName,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,UP
                  | $backendId1,$name1,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,UP
                  | $backendId2,$name2,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,DOWN
                 """.stripMargin

            Then("The ha proxy ignores backends, frontends, and fields")
            haProxy.parseResponse(response) shouldBe (Set(name1), Set(name2))

            val backendId3 = UUID.randomUUID()
            val backendId4 = UUID.randomUUID()
            val name3 = UUID.randomUUID()
            val name4 = UUID.randomUUID()

            When("Parsing an ha proxy response with incorrectly formatted lines")
            response =
                s"""
                  | $backendId1,$name1,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,UP
                  | $backendId2,$name2,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,UP
                  | $backendId3,$name3,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,TOTO
                  | $backendId4,$name4,0,0,0,0,0,0,0,0,0,0,0,0,0,0,DOWN
                """.stripMargin

            Then("The ha proxy incorrectly formatted lines")
            haProxy.parseResponse(response) shouldBe (Set(name1, name2),
                                                      Set.empty)

            When("Making a unix domain channel")
            Then("No exceptions are raised")
            haProxy.makeChannel()

            When("Writing the pool configuration to a file")
            haProxy.shouldWriteConf = true
            haProxy.writeConf(poolConfig)
            val tmpPath = FileUtils.getTempDirectoryPath
            val file = new File(tmpPath + "/" + poolId.toString + "/" +
                                PoolConfig.CONF)
            file.exists() shouldBe true
            file.delete()

            When("Setting member status to inactive")
            haProxy.setMembersStatus(Set.empty, Set(memberId))

            Then("The member should be inactive")
            var m = backend.store.get(classOf[PoolMember],
                                      memberId).value.get.get
            m.getStatus shouldBe LBStatus.INACTIVE

            When("Setting member status to active")
            haProxy.setMembersStatus(Set(memberId), Set.empty)

            Then("The member should be active")
            m = backend.store.get(classOf[PoolMember],
                                  memberId).value.get.get
            m.getStatus shouldBe LBStatus.ACTIVE
        }
    }

    feature("IP class") {
        scenario("public methods") {
            var ip = ipMockWithSetup()

            val cmd = "toto"
            ip.link(cmd)
            verify(ip).exec(s"ip link $cmd")

            ip.ifaceExists(cmd)
            verify(ip).execNoErrors(s"ip link show $cmd")

            ip.netns(cmd)
            verify(ip).exec(s"ip netns $cmd")

            ip.netnsGetOutput(cmd)
            verify(ip).execGetOutput(s"ip netns $cmd")

            ip.execIn(ns = "", cmd)
            verify(ip).exec(s"ip netns $cmd")

            val namespace = "vortex"
            ip.execIn(namespace, cmd)
            verify(ip).exec(s"ip netns exec $namespace $cmd")

            ip.addNS(namespace)
            verify(ip).exec(s"ip netns add $namespace")

            ip.deleteNS(namespace)
            verify(ip).exec(s"ip netns del $namespace")

            ip.namespaceExist(namespace)
            verify(ip).execGetOutput(s"ip netns list")

            ip = ipMockWithSetup()

            ip.ensureNamespace(namespace)
            verify(ip, times(1)).exec(s"ip netns add $namespace")

            val namespaces = new util.LinkedList[String]()
            namespaces.add(namespace)
            Mockito.doReturn(namespaces).when(ip)
                   .execGetOutput(org.mockito.Matchers.anyString())
            ip.ensureNamespace(namespace)
            verify(ip, times(1)).exec(s"ip netns add $namespace")

            val interface = "eth0"
            ip.ensureNoInterface(interface) shouldBe
            verify(ip).exec(s"ip link delete $interface")

            Mockito.doReturn(-1).when(ip)
                   .execNoErrors(org.mockito.Matchers.anyString())
            ip.ensureNoInterface(interface)
            verify(ip).exec(s"ip link delete $interface")

            ip.interfaceExistsInNs(namespace, interface) shouldBe true
            verify(ip).exec(s"ip netns exec $namespace ip link show $interface")

            Mockito.doReturn(new util.LinkedList[String]()).when(ip)
                   .execGetOutput(org.mockito.Matchers.anyString())
            ip.interfaceExistsInNs(namespace, interface) shouldBe false
            verify(ip).exec(s"ip netns exec $namespace ip link show $interface")

            val mirrorInterface = "mirror"
            ip.preparePair(interface, mirrorInterface, namespace)
            verify(ip).exec(s"ip link add name $interface type veth peer name " +
                            s"$mirrorInterface")
            verify(ip).exec(s"ip link set $mirrorInterface netns $namespace")

            ip.configureUp(interface, namespace)
            verify(ip).exec(s"ip netns exec $namespace ip link set dev " +
                            s"$interface up")

            ip.configureDown(interface, namespace)
            verify(ip).exec(s"ip netns exec $namespace ip link set dev " +
                            s"$interface down")

            val mac = MAC.random()
            ip.configureMac(interface, mac.toString, namespace)
            verify(ip).exec(s"ip netns exec $namespace ip link set dev " +
                            s"$interface up address $mac")

            val addr = IPv4Addr.random
            ip.configureIp(interface, addr.toString, namespace)
            verify(ip).exec(s"ip netns exec $namespace ip addr add $addr dev " +
                            s"$interface")
        }
    }
}
