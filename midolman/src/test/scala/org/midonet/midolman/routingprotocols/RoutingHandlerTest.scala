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

import java.util
import java.util.UUID
import scala.concurrent.Future

import akka.actor._
import akka.testkit.TestActorRef
import org.junit.runner.RunWith
import org.mockito.ArgumentMatcher
import org.mockito.Mockito._
import org.mockito.Matchers._
import org.scalatest._
import org.scalatest.junit.JUnitRunner
import org.scalatest.mock.MockitoSugar

import org.midonet.cluster.DataClient
import org.midonet.cluster.data.Route
import org.midonet.midolman.BackChannelMessage
import org.midonet.midolman.config.MidolmanConfig
import org.midonet.midolman.topology.devices.RouterPort
import org.midonet.packets.{MAC, IPv4Addr, IPv4Subnet}
import org.midonet.quagga.BgpdConfiguration.{Network, Neighbor, BgpRouter}
import org.midonet.quagga.ZebraProtocol.RIBType
import org.midonet.quagga.{BgpConnection, BgpdProcess}
import org.midonet.odp.DpPort
import org.midonet.odp.ports.NetDevPort

@RunWith(classOf[JUnitRunner])
class RoutingHandlerTest extends FeatureSpecLike
                                    with Matchers
                                    with BeforeAndAfter
                                    with MockitoSugar {
    var rport: RouterPort = _
    var bgpd: MockBgpdProcess = _
    var dataClient: DataClient = _
    def vty = bgpd.vty
    var routingHandler: ActorRef = _
    var invalidations = List[BackChannelMessage]()
    val config = MidolmanConfig.forTests
    implicit var as: ActorSystem = _

    val MY_AS = 7

    val peer1 = IPv4Addr.fromString("192.168.80.2")
    val peer1Id = UUID.randomUUID()
    def baseConfig = new BgpRouter(MY_AS, rport.portIp,
                                    Map(peer1 -> Neighbor(peer1, 100)))

    val peer2 = IPv4Addr.fromString("192.168.80.3")
    val peer2Id = UUID.randomUUID()

    before {
        rport = new RouterPort()
        rport.routerId = UUID.randomUUID()
        rport.portSubnet = IPv4Subnet.fromCidr("192.168.80.0/24")
        rport.portIp = IPv4Addr.fromString("192.168.80.1")
        rport.id = UUID.randomUUID()
        rport.portMac = MAC.random()
        rport.afterFromProto(null)

        as = ActorSystem("RoutingHandlerTest")
        bgpd = new MockBgpdProcess
        dataClient = mock[DataClient]
        invalidations = Nil
        routingHandler = TestActorRef(new TestableRoutingHandler(rport,
                                                    invalidations ::= _,
                                                    dataClient,
                                                    config,
                                                    bgpd))

        routingHandler ! rport
        bgpd.state should be (bgpd.NOT_STARTED)
        routingHandler ! RoutingHandler.Update(baseConfig, Set(peer1Id))
        bgpd.state should be (bgpd.RUNNING)
        bgpd.starts should be (1)
        reset(bgpd.vty)
    }

    after {
        as.stop(routingHandler)
        as.shutdown()
    }

    feature ("manages the bgpd lifecycle") {
        scenario("starts and stops bgpd") {
            routingHandler ! RoutingHandler.Update(baseConfig.copy(neighbors = Map.empty), Set.empty)
            bgpd.state should be (bgpd.NOT_STARTED)

            routingHandler ! RoutingHandler.Update(baseConfig, Set(peer1Id))
            bgpd.state should be (bgpd.RUNNING)
        }

        scenario("change in the router port address") {
            val p = rport.copy(true)
            p.portIp = IPv4Addr.fromString("192.168.80.2")
            p.afterFromProto(null)
            routingHandler ! p
            bgpd.state should be (bgpd.RUNNING)
            bgpd.starts should be (2)

            routingHandler ! p.copy(true)
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
            routingHandler ! RoutingHandler.FETCH_BGPD_STATUS
            bgpd.state should be (bgpd.RUNNING)
            bgpd.starts should be (2)
        }
    }

    def matchRoute(dst: String, gw: String): ArgumentMatcher[Route] = new ArgumentMatcher[Route] {
        override def matches(o: AnyRef): Boolean = {
            val r = o.asInstanceOf[Route]
            val dstNet = IPv4Subnet.fromCidr(dst)
            println(s"ROUTE: ${r.getDstNetworkAddr}")
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

    feature("learns routes") {
        scenario("peer announces a new route") {
            val dst = "10.10.10.0/24"
            val gw = "192.168.80.254"

            routingHandler ! RoutingHandler.AddPeerRoute(RIBType.BGP,
                IPv4Subnet.fromCidr(dst), IPv4Addr.fromString(gw), 100)

            verify(dataClient).routesCreateEphemeral(argThat(matchRoute(dst, gw)))
        }

        scenario("peer stops announcing a route") {
            val dst = "10.10.10.0/24"
            val gw = "192.168.80.254"
            val routeId = UUID.randomUUID()

            when(dataClient.routesCreateEphemeral(anyObject())).thenReturn(routeId)

            routingHandler ! RoutingHandler.AddPeerRoute(RIBType.BGP,
                IPv4Subnet.fromCidr(dst), IPv4Addr.fromString(gw), 100)
            routingHandler ! RoutingHandler.RemovePeerRoute(RIBType.BGP,
                IPv4Subnet.fromCidr(dst), IPv4Addr.fromString(gw))

            verify(dataClient).routesCreateEphemeral(argThat(matchRoute(dst, gw)))
            verify(dataClient).routesDelete(routeId)
        }

        scenario("routes are synced after a glitch") {
            val dst1 = "10.10.10.0/24"
            val dst2 = "10.10.20.0/24"
            val dst3 = "10.10.30.0/24"
            val gw = "192.168.80.254"

            val emptyList: util.List[Route] = new util.ArrayList()
            when(dataClient.routesFindByRouter(anyObject())).thenReturn(emptyList)
            when(dataClient.routesCreateEphemeral(anyObject())).thenReturn(RoutingHandler.NO_UUID)
            when(dataClient.routesCreateEphemeral(anyObject())).thenReturn(RoutingHandler.NO_UUID)
            when(dataClient.routesCreateEphemeral(anyObject())).thenReturn(RoutingHandler.NO_UUID)

            routingHandler ! RoutingHandler.AddPeerRoute(RIBType.BGP,
                IPv4Subnet.fromCidr(dst1), IPv4Addr.fromString(gw), 100)
            routingHandler ! RoutingHandler.AddPeerRoute(RIBType.BGP,
                IPv4Subnet.fromCidr(dst2), IPv4Addr.fromString(gw), 100)
            routingHandler ! RoutingHandler.AddPeerRoute(RIBType.BGP,
                IPv4Subnet.fromCidr(dst3), IPv4Addr.fromString(gw), 100)
            routingHandler ! RoutingHandler.RemovePeerRoute(RIBType.BGP,
                IPv4Subnet.fromCidr(dst3), IPv4Addr.fromString(gw))

            verify(dataClient).routesCreateEphemeral(argThat(matchRoute(dst1, gw)))
            verify(dataClient).routesCreateEphemeral(argThat(matchRoute(dst2, gw)))
            verify(dataClient).routesCreateEphemeral(argThat(matchRoute(dst3, gw)))

            reset(dataClient)
            routingHandler ! RoutingHandler.SYNC_PEER_ROUTES

            verify(dataClient, times(2)).routesCreateEphemeral(anyObject())
            verify(dataClient).routesCreateEphemeral(argThat(matchRoute(dst1, gw)))
            verify(dataClient).routesCreateEphemeral(argThat(matchRoute(dst2, gw)))
        }
    }

    feature("reacts to changes in the bgp session configuration") {
        scenario("a new peer is added or removed") {
            val update = new BgpRouter(MY_AS, rport.portIp,
                    Map(peer1 -> Neighbor(peer1, 100),
                        peer2 -> Neighbor(peer2, 200)))

            routingHandler ! RoutingHandler.Update(update, Set(peer1Id, peer2Id))
            routingHandler ! RoutingHandler.Update(baseConfig, Set(peer1Id))
            verify(bgpd.vty).addPeer(MY_AS, peer2, 200,
                                     config.bgpKeepAlive,
                                     config.bgpHoldTime,
                                     config.bgpConnectRetry)
            verify(bgpd.vty).deletePeer(MY_AS, peer2)
        }

        scenario("routes are announced and removed") {
            val net1 = Network(IPv4Subnet.fromCidr("10.99.10.0/24"))
            val net2 = Network(IPv4Subnet.fromCidr("10.99.20.0/24"))
            val net3 = Network(IPv4Subnet.fromCidr("10.99.30.0/24"))

            routingHandler ! RoutingHandler.Update(
                baseConfig.copy(networks = Set(net1, net2, net3)), Set(peer1Id))
            routingHandler ! RoutingHandler.Update(
                baseConfig.copy(networks = Set(net1)), Set(peer1Id))

            verify(bgpd.vty, times(3)).addNetwork(anyInt(), anyObject())
            verify(bgpd.vty).addNetwork(MY_AS, net1.cidr)
            verify(bgpd.vty).addNetwork(MY_AS, net2.cidr)
            verify(bgpd.vty).addNetwork(MY_AS, net3.cidr)

            verify(bgpd.vty, times(2)).deleteNetwork(anyInt(), anyObject())
            verify(bgpd.vty).deleteNetwork(MY_AS, net2.cidr)
            verify(bgpd.vty).deleteNetwork(MY_AS, net3.cidr)
        }

        scenario("timer values change") {
            val update = new BgpRouter(MY_AS, rport.portIp,
                Map(peer1 -> Neighbor(peer1, 100, Some(29), Some(30), Some(31))))

            routingHandler ! RoutingHandler.Update(update, Set(peer1Id))
            verify(bgpd.vty).addPeer(MY_AS, peer1, 100, 29, 30, 31)
        }
    }
}

class MockBgpdProcess extends BgpdProcess with MockitoSugar {
    val NOT_STARTED = "NOT_STARTED"
    val PREPARED = "PREPARED"
    val RUNNING = "RUNNING"

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
        if (state != PREPARED)
            throw new Exception(s"Illegal state: $state")
        state = RUNNING
        died = false
        starts += 1
    }
}


class TestableRoutingHandler(rport: RouterPort,
                             flowInvalidator: (BackChannelMessage) => Unit,
                             dataClient: DataClient,
                             config: MidolmanConfig,
                             override val bgpd: MockBgpdProcess)
            extends RoutingHandler(rport, 1, flowInvalidator, dataClient,
                                   config, new MockZkConnWatcher()) {

    override def createDpPort(port: String): Future[(DpPort, Int)]  = {
        val p = DpPort.fakeFrom(new NetDevPort("bgpd"), 27).asInstanceOf[NetDevPort]
        Future.successful((p, 27))
    }

    override def deleteDpPort(port: NetDevPort): Future[_] = Future.successful(true)
    override def startZebra(): Unit = {}
    override def stopZebra(): Unit = {}
}
