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

package org.midonet.midolman.routingprotocols

import java.util.UUID

import scala.collection.mutable
import scala.collection.JavaConverters._
import scala.concurrent.{Future, Promise}

import akka.actor._
import akka.testkit.TestActorRef

import org.junit.runner.RunWith
import org.mockito.ArgumentMatcher
import org.mockito.Matchers.{eq => Eq, _}
import org.mockito.Mockito._
import org.scalatest._
import org.scalatest.junit.JUnitRunner
import org.scalatest.mock.MockitoSugar

import org.midonet.cluster.backend.zookeeper.StateAccessException
import org.midonet.cluster.data.Route
import org.midonet.midolman.config.MidolmanConfig
import org.midonet.midolman.routingprotocols.RoutingHandler.PeerRoute
import org.midonet.midolman.routingprotocols.RoutingHandler.PortBgpInfos
import org.midonet.midolman.routingprotocols.RoutingManagerActor.RoutingStorage
import org.midonet.midolman.simulation.RouterPort
import org.midonet.midolman.topology.PortBgpInfo
import org.midonet.midolman.topology.devices.BgpPort
import org.midonet.odp.DpPort
import org.midonet.odp.ports.NetDevPort
import org.midonet.packets.{IPSubnet, IPv4Addr, IPv4Subnet, MAC}
import org.midonet.quagga.BgpdConfiguration.{BgpRouter, Neighbor, Network}
import org.midonet.quagga.ZebraProtocol.RIBType
import org.midonet.quagga.{BgpConnection, BgpdProcess, ZebraPath}
import org.midonet.sdn.flows.FlowTagger.FlowTag

@RunWith(classOf[JUnitRunner])
class RoutingHandlerTest extends FeatureSpecLike
                                    with Matchers
                                    with BeforeAndAfter
                                    with MockitoSugar {
    var rport: RouterPort = _
    var bgpd: MockBgpdProcess = _
    var routingStorage: MockRoutingStorage = _
    def vty = bgpd.vty
    var routingHandler: ActorRef = _
    var invalidations = List[FlowTag]()
    val config = MidolmanConfig.forTests
    implicit var as: ActorSystem = _
    val peerRouteToPortAccount = mutable.Map[PeerRoute, UUID]()

    val asNumber = 7

    val peer1 = IPv4Addr.fromString("192.168.80.2")
    val peer1Id = UUID.randomUUID()
    def baseConfig = BgpRouter(asNumber, rport.portAddress4.getAddress,
                               Map(peer1 -> Neighbor(peer1, 100)))

    val peer2 = IPv4Addr.fromString("192.168.80.3")
    val peer2Id = UUID.randomUUID()

    before {
        as = ActorSystem("RoutingHandlerTest")

        rport = RouterPort(
            id = UUID.randomUUID(),
            tunnelKey = 1,
            isPortActive = true,
            routerId = UUID.randomUUID(),
            portAddresses =
                List[IPSubnet[_]](IPv4Subnet.fromCidr("192.168.80.1/24")).asJava,
            portAddress4 = IPv4Subnet.fromCidr("192.168.80.1/24"),
            portAddress6 = null,
            portMac = MAC.random())

        bgpd = new MockBgpdProcess

        routingStorage = spy(new MockRoutingStorage())
        invalidations = Nil

        routingHandler = TestActorRef(new TestableRoutingHandler(rport,
                                                    invalidations ::= _,
                                                    routingStorage,
                                                    config,
                                                    bgpd,
                                                    false,
                                                    peerRouteToPortAccount))
        routingHandler ! rport
        bgpd.state should be (bgpd.NOT_STARTED)
        routingHandler ! BgpPort(rport, baseConfig, Set(peer1Id))
        bgpd.state should be (bgpd.RUNNING)
        bgpd.starts should be (1)
        reset(bgpd.vty)
    }

    after {
        as.stop(routingHandler)
        as.shutdown()
    }

    feature("Handles container ports") {
        scenario("Start BGP daemon for a container port") {
            val ifaceName = "TESTING"
            val containerRport = rport.copy(interfaceName = "TESTING",
                                            containerId = UUID.randomUUID())

            val containerRoutingHandler = TestActorRef(
                new TestableRoutingHandler(containerRport,
                                           invalidations ::= _,
                                           routingStorage,
                                           config,
                                           bgpd,
                                           true,
                                           peerRouteToPortAccount))

            containerRoutingHandler ! containerRport
            containerRoutingHandler ! BgpPort(containerRport, baseConfig,
                                              Set(peer1Id))
        }

        scenario("Ignores routers with no AS numbers") {
            val ifaceName = "TESTING"
            val containerRport = rport.copy(interfaceName = "TESTING",
                                            containerId = UUID.randomUUID())
            val bgpRouter = BgpRouter(-1, rport.portAddress4.getAddress)

            val containerRoutingHandler = TestActorRef(
                new TestableRoutingHandler(containerRport,
                                           invalidations ::= _,
                                           routingStorage,
                                           config,
                                           bgpd,
                                           true,
                                           peerRouteToPortAccount))

            containerRoutingHandler ! containerRport
            containerRoutingHandler ! BgpPort(containerRport, bgpRouter,
                                              Set(peer1Id))
        }

        scenario("Applies and removes static ARP entries") {
            val containerPort = rport.copy(interfaceName = "TESTING",
                                            containerId = UUID.randomUUID())
            val containerRoutingHandler = TestActorRef(
                new TestableRoutingHandler(containerPort,
                    invalidations ::= _,
                    routingStorage,
                    config,
                    bgpd,
                    true,
                    peerRouteToPortAccount))

            containerRoutingHandler ! containerPort
            containerRoutingHandler ! BgpPort(containerPort, baseConfig.copy(neighbors = Map.empty), Set.empty)
            bgpd.currentArpEntries.size should be (0)
            val pbi1 = PortBgpInfo(UUID.randomUUID(), "fakemac", "fakecidr", peer1.toString)
            val pbi2 = PortBgpInfo(UUID.randomUUID(), "fakemac2", "fakecidr2", peer2.toString)

            containerRoutingHandler ! PortBgpInfos(Seq(pbi1))
            containerRoutingHandler ! BgpPort(containerPort, baseConfig, Set(peer1Id))
            bgpd.currentArpEntries should contain theSameElementsAs Set(peer1.toString)

            val update = BgpRouter(asNumber, rport.portAddress4.getAddress,
                                   Map(peer1 -> Neighbor(peer1, 100),
                    peer2 -> Neighbor(peer2, 200)))
            containerRoutingHandler ! PortBgpInfos(Seq(pbi1, pbi2))
            containerRoutingHandler ! BgpPort(containerPort, update, Set(peer1Id, peer2Id))
            bgpd.currentArpEntries should contain theSameElementsAs Set(peer1.toString, peer2.toString)

            containerRoutingHandler ! PortBgpInfos(Seq(pbi1))
            containerRoutingHandler ! BgpPort(containerPort, baseConfig, Set(peer1Id))
            bgpd.currentArpEntries should contain theSameElementsAs Set(peer1.toString)

            containerRoutingHandler ! PortBgpInfos(Seq())
            containerRoutingHandler ! BgpPort(containerPort, baseConfig.copy(neighbors = Map.empty), Set.empty)
            bgpd.currentArpEntries.size should be (0)
        }
    }

    private def toPortBgpInfoSeq(cidrs: String*): Seq[PortBgpInfo] = {
        for (cidr <- cidrs.toSeq) yield
            PortBgpInfo(null, null, cidr, null)
    }

    feature("manages router ip addrs") {
        scenario("adds and deletes ips") {
            routingHandler ! RoutingHandler.PortBgpInfos(Seq())
            bgpd.currentIps.size should be (0)
            var pbis = toPortBgpInfoSeq("1.1.1.1/24")
            routingHandler ! RoutingHandler.PortBgpInfos(pbis)
            bgpd.currentIps should contain theSameElementsAs pbis.map(_.cidr)
            pbis = toPortBgpInfoSeq("1.1.1.1/24", "1.1.1.2/24")
            routingHandler ! RoutingHandler.PortBgpInfos(pbis)
            bgpd.currentIps should contain theSameElementsAs pbis.map(_.cidr)
            pbis = toPortBgpInfoSeq("1.1.1.2/24")
            routingHandler ! RoutingHandler.PortBgpInfos(pbis)
            bgpd.currentIps should contain theSameElementsAs pbis.map(_.cidr)
            pbis = toPortBgpInfoSeq("2.2.2.2/24")
            routingHandler ! RoutingHandler.PortBgpInfos(pbis)
            bgpd.currentIps should contain theSameElementsAs pbis.map(_.cidr)
            pbis = toPortBgpInfoSeq("1.1.1.1/24", "1.1.1.2/24")
            routingHandler ! RoutingHandler.PortBgpInfos(pbis)
            bgpd.currentIps should contain theSameElementsAs pbis.map(_.cidr)
            pbis = toPortBgpInfoSeq("1.1.1.2/24")
            routingHandler ! RoutingHandler.PortBgpInfos(pbis)
            bgpd.currentIps should contain theSameElementsAs pbis.map(_.cidr)
        }
    }

    feature("manages the bgpd lifecycle") {
        scenario("starts and stops bgpd") {
            routingHandler ! BgpPort(rport, baseConfig.copy(neighbors = Map.empty), Set.empty)
            bgpd.state should be (bgpd.NOT_STARTED)

            routingHandler ! BgpPort(rport, baseConfig, Set(peer1Id))
            bgpd.state should be (bgpd.RUNNING)
        }

        scenario("change in the router port address") {
            val p = rport.copy(isPortActive = true,
                               portAddress4 = IPv4Subnet.fromCidr("192.168.80.2/24"))
            routingHandler ! BgpPort(p, baseConfig, Set(peer1Id))
            bgpd.state should be (bgpd.RUNNING)
            bgpd.starts should be (2)

            routingHandler ! BgpPort(p, baseConfig, Set(peer1Id))
            bgpd.state should be (bgpd.RUNNING)
            bgpd.starts should be (2)
        }

        scenario("port goes up and down") {
            routingHandler ! RoutingHandler.PortActive(false)
            bgpd.state should be (bgpd.NOT_STARTED)

            routingHandler ! RoutingHandler.PortActive(true)
            bgpd.state should be (bgpd.RUNNING)
        }

        scenario("zookepeer goes on and off") {
            routingHandler ! RoutingHandler.ZookeeperConnected(false)
            bgpd.state should be (bgpd.NOT_STARTED)

            routingHandler ! RoutingHandler.ZookeeperConnected(true)
            bgpd.state should be (bgpd.RUNNING)
        }

        scenario("zookeeper and the port go on and off") {
            routingHandler ! RoutingHandler.ZookeeperConnected(false)
            bgpd.state should be (bgpd.NOT_STARTED)

            routingHandler ! RoutingHandler.PortActive(false)
            routingHandler ! RoutingHandler.ZookeeperConnected(true)
            bgpd.state should be (bgpd.NOT_STARTED)

            routingHandler ! RoutingHandler.ZookeeperConnected(false)
            routingHandler ! RoutingHandler.PortActive(true)
            bgpd.state should be (bgpd.NOT_STARTED)

            routingHandler ! RoutingHandler.ZookeeperConnected(true)
            bgpd.state should be (bgpd.RUNNING)
        }

        scenario("bgp dies") {
            bgpd.die()
            routingHandler ! RoutingHandler.FetchBgpdStatus
            bgpd.state should be (bgpd.RUNNING)
            bgpd.starts should be (2)
        }
    }

    def matchRoute(dst: String, gw: String): ArgumentMatcher[Route] = new ArgumentMatcher[Route] {
        override def matches(o: AnyRef): Boolean = {
            val r = o.asInstanceOf[Route]
            val dstNet = IPv4Subnet.fromCidr(dst)
            if (!r.isLearned)
                false
            else if (r.getDstNetworkAddr != dstNet.getAddress.toString)
                false
            else if (r.getDstNetworkLength != dstNet.getPrefixLen)
                false
            else if (r.getNextHopGateway != gw)
                false
            else
                true
        }
    }

    def pushRoute(dst: String, gws: String*): Unit = {
        val addrs = (gws map (gw => IPv4Addr.fromString(gw))).toSet
        routingHandler ! RoutingHandler.AddPeerRoutes(
            IPv4Subnet.fromCidr(dst),
            addrs map (gw => ZebraPath(RIBType.BGP, gw, 100)))
    }

    def pullRoute(dst: IPv4Subnet, gw: IPv4Addr): Unit = {
        routingHandler ! RoutingHandler.RemovePeerRoute(RIBType.BGP, dst, gw)
    }

    feature("learns routes") {
        scenario("peer announces a new route") {
            val dst = "10.10.10.0/24"
            val gw = "192.168.80.254"

            pushRoute(dst, gw)
            verify(routingStorage).addRoute(argThat(matchRoute(dst, gw)),
                                            Eq(rport.id))
        }

        scenario("peer route map gets updated") {
            val dst = "10.10.10.0/24"
            val dstSub = IPv4Subnet.fromCidr(dst)
            val gw1 = "192.168.80.254"
            val gw1Addr = IPv4Addr.fromString(gw1)
            val gw2 = "192.168.80.253"
            val gw2Addr = IPv4Addr.fromString(gw2)

            pushRoute(dst, gw1)
            var expectedMap = Map(PeerRoute(dstSub, gw1Addr) -> rport.id)
            peerRouteToPortAccount shouldBe expectedMap

            pushRoute(dst, gw2)
            expectedMap = Map(PeerRoute(dstSub, gw1Addr) -> rport.id,
                              PeerRoute(dstSub, gw2Addr) -> rport.id)
            peerRouteToPortAccount shouldBe expectedMap

            pullRoute(dstSub, gw1Addr)
            expectedMap = Map(PeerRoute(dstSub, gw2Addr) -> rport.id)
            peerRouteToPortAccount shouldBe expectedMap

            pullRoute(dstSub, gw2Addr)
            peerRouteToPortAccount shouldBe empty
        }

        scenario("multipath routes") {
            val dst = "10.10.10.0/24"
            val gw1 = "192.168.80.254"
            val gw2 = "192.168.80.253"
            val order = org.mockito.Mockito.inOrder(routingStorage)

            pushRoute(dst, gw1)
            order.verify(routingStorage).addRoute(argThat(matchRoute(dst, gw1)),
                                                  Eq(rport.id))

            pushRoute(dst, gw2)
            order.verify(routingStorage).addRoute(argThat(matchRoute(dst, gw2)),
                                                  Eq(rport.id))
            order.verify(routingStorage).removeRoute(argThat(matchRoute(dst, gw1)),
                                                     Eq(rport.id))

            pushRoute(dst, gw1, gw2)
            order.verify(routingStorage).addRoute(argThat(matchRoute(dst, gw1)),
                                                  Eq(rport.id))

            pushRoute(dst, gw1)
            order.verify(routingStorage).removeRoute(argThat(matchRoute(dst, gw2)),
                                                     Eq(rport.id))
        }

        scenario("peer stops announcing a route") {
            val dst = "10.10.10.0/24"
            val gw = "192.168.80.254"

            pushRoute(dst, gw)
            routingHandler ! RoutingHandler.RemovePeerRoute(RIBType.BGP,
                IPv4Subnet.fromCidr(dst), IPv4Addr.fromString(gw))

            verify(routingStorage).addRoute(argThat(matchRoute(dst, gw)),
                                            Eq(rport.id))
            verify(routingStorage).removeRoute(argThat(matchRoute(dst, gw)),
                                               Eq(rport.id))
        }

        scenario("routes are synced after a glitch") {
            val dst1 = "10.10.10.0/24"
            val dst2 = "10.10.20.0/24"
            val dst3 = "10.10.30.0/24"
            val gw = "192.168.80.254"

            routingStorage.break()

            pushRoute(dst1, gw)
            pushRoute(dst2, gw)
            pushRoute(dst3, gw)
            routingHandler ! RoutingHandler.RemovePeerRoute(RIBType.BGP,
                IPv4Subnet.fromCidr(dst3), IPv4Addr.fromString(gw))

            verify(routingStorage).addRoute(argThat(matchRoute(dst1, gw)),
                                            Eq(rport.id))
            verify(routingStorage).addRoute(argThat(matchRoute(dst2, gw)),
                                            Eq(rport.id))
            verify(routingStorage).addRoute(argThat(matchRoute(dst3, gw)),
                                            Eq(rport.id))

            routingStorage.unbreak()
            reset(routingStorage)

            routingHandler ! RoutingHandler.SyncPeerRoutes

            verify(routingStorage, times(2)).addRoute(anyObject(),
                                                      Eq(rport.id))
            verify(routingStorage).addRoute(argThat(matchRoute(dst1, gw)),
                                            Eq(rport.id))
            verify(routingStorage).addRoute(argThat(matchRoute(dst2, gw)),
                                            Eq(rport.id))
        }
    }

    feature("reacts to changes in the bgp session configuration") {
        scenario("a new peer is added or removed") {
            val update = BgpRouter(asNumber, rport.portAddress4.getAddress,
                                   Map(peer1 -> Neighbor(peer1, 100),
                        peer2 -> Neighbor(peer2, 200)))

            routingHandler ! BgpPort(rport, update, Set(peer1Id, peer2Id))
            routingHandler ! BgpPort(rport, baseConfig, Set(peer1Id))
            verify(bgpd.vty).addPeer(asNumber, Neighbor(peer2, 200))
            verify(bgpd.vty).deletePeer(asNumber, peer2)
        }

        scenario("routes are announced and removed") {
            val net1 = Network(IPv4Subnet.fromCidr("10.99.10.0/24"))
            val net2 = Network(IPv4Subnet.fromCidr("10.99.20.0/24"))
            val net3 = Network(IPv4Subnet.fromCidr("10.99.30.0/24"))

            routingHandler ! BgpPort(rport,
                baseConfig.copy(networks = Set(net1, net2, net3)), Set(peer1Id))
            routingHandler ! BgpPort(rport,
                baseConfig.copy(networks = Set(net1)), Set(peer1Id))

            verify(bgpd.vty, times(3)).addNetwork(anyInt(), anyObject())
            verify(bgpd.vty).addNetwork(asNumber, net1.cidr)
            verify(bgpd.vty).addNetwork(asNumber, net2.cidr)
            verify(bgpd.vty).addNetwork(asNumber, net3.cidr)

            verify(bgpd.vty, times(2)).deleteNetwork(anyInt(), anyObject())
            verify(bgpd.vty).deleteNetwork(asNumber, net2.cidr)
            verify(bgpd.vty).deleteNetwork(asNumber, net3.cidr)
        }

        scenario("timer values change") {
            val update = BgpRouter(asNumber, rport.portAddress4.getAddress,
                                   Map(peer1 -> Neighbor(peer1, 100, Some(29), Some(30), Some(31))))

            routingHandler ! BgpPort(rport, update, Set(peer1Id))
            verify(bgpd.vty).addPeer(asNumber, Neighbor(peer1, 100, Some(29), Some(30), Some(31)))
        }
    }
}

class MockRoutingStorage extends RoutingStorage {
    var broken = false

    def break(): Unit = { broken = true }
    def unbreak(): Unit = { broken = false }

    override def setStatus(portId: UUID, status: String): Future[UUID] = {
        if (broken) {
            Promise.failed(new StateAccessException("whatever")).future
        } else {
            Promise.successful(portId).future
        }
    }

    override def addRoute(route: Route, portId: UUID): Future[Route] = {
        if (broken) {
            Promise.failed(new StateAccessException("whatever")).future
        } else {
            Promise.successful(route).future
        }
    }

    override def removeRoute(route: Route, portId: UUID): Future[Route] = {
        if (broken) {
            Promise.failed(new StateAccessException("whatever")).future
        } else {
            Promise.successful(route).future
        }
    }

    override def learnedRoutes(routerId: UUID, portId: UUID, hostId: UUID)
    : Future[Set[Route]] = {
        if (broken) {
            Promise.failed(new StateAccessException("whatever")).future
        } else {
            Promise.successful(Set[Route]()).future
        }
    }
}

class MockBgpdProcess extends BgpdProcess with MockitoSugar {
    val NOT_STARTED = "NOT_STARTED"
    val PREPARED = "PREPARED"
    val RUNNING = "RUNNING"

    var currentIps: Set[String] = Set.empty
    var currentArpEntries: Set[String] = Set.empty

    override val vty = mock[BgpConnection]

    var state = NOT_STARTED

    var starts = 0
    private var died = false

    def die(): Unit = {
        died = true
    }

    override def prepare(): Unit = {
        if (state != NOT_STARTED)
            throw new Exception(s"Illegal state: $state")
        state = PREPARED
    }

    override def stop(): Boolean = {
        if (state != NOT_STARTED) {
            state = NOT_STARTED
        }
        true
    }

    override def isAlive: Boolean = (state == RUNNING) && !died

    override def start(): Unit = {
        state = RUNNING
        died = false
        starts += 1
    }

    override def addAddress(iface: String, ip: String, mac: String): Unit = {
        currentIps += ip
    }

    override def removeAddress(iface: String, ip: String): Unit = {
        currentIps -= ip
    }

    def addArpEntry(iface: String, ip: String, mac: String, peerIp: String): Unit = {
        currentArpEntries += ip
    }

    def removeArpEntry(iface: String, ip: String, peerIp: String): Unit = {
        currentArpEntries -= ip
    }
}


class TestableRoutingHandler(rport: RouterPort,
                             flowInvalidator: FlowTag => Unit,
                             routingStorage: RoutingStorage,
                             config: MidolmanConfig,
                             override val bgpd: MockBgpdProcess,
                             isQuagga: Boolean,
                             peerPortMap: mutable.Map[PeerRoute, UUID])
            extends RoutingHandler(rport, 1, flowInvalidator, routingStorage,
                                   config, new MockZkConnWatcher(), isQuagga) {

    override val peerRouteToPort = peerPortMap

    override def createDpPort(port: String): Future[(DpPort, Int)]  = {
        val p = DpPort.fakeFrom(new NetDevPort("bgpd"), 27).asInstanceOf[NetDevPort]
        Future.successful((p, 27))
    }

    override def deleteDpPort(port: NetDevPort): Future[_] = Future.successful(true)
    override def startZebra(): Unit = {}
    override def stopZebra(): Unit = {}
}
