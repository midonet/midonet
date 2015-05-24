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
import java.util.concurrent.{TimeoutException, TimeUnit, CountDownLatch}

import akka.actor._
import org.midonet.cluster.DataClient
import org.midonet.cluster.data.Route
import org.midonet.midolman.BackChannelMessage
import org.midonet.midolman.config.MidolmanConfig
import org.midonet.packets.{MAC, IPv4Addr, IPv4Subnet}
import org.midonet.quagga.BgpdConfiguration.{Neighbor, BgpRouter}
import org.midonet.quagga.ZebraProtocol.RIBType
import org.midonet.quagga.{BgpConnection, BgpdProcess}
import org.mockito.ArgumentMatcher
import org.scalatest.mock.MockitoSugar
import org.mockito.Mockito._
import org.mockito.Matchers._

import scala.concurrent.duration.Duration
import scala.concurrent.{ExecutionContextExecutor, Future}

import org.junit.runner.RunWith
import org.scalatest._
import org.scalatest.junit.JUnitRunner

import org.midonet.odp.ports.NetDevPort
import org.midonet.odp.DpPort
import org.midonet.midolman.topology.devices.RouterPort

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


    val peer1 = IPv4Addr.fromString("192.168.80.2")
    val peer1Id = UUID.randomUUID()
    def baseConfig = new BgpRouter(42, rport.portIp,
                                    Map(peer1 -> Neighbor(peer1, 100)))

    private def sync(): Unit = {
        val latch = new CountDownLatch(1)
        routingHandler ! latch
        if (!latch.await(3000, TimeUnit.MILLISECONDS))
            throw new TimeoutException("Failed to synchronize with RoutingHandler")
    }

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
        routingHandler = as.actorOf(Props(new TestableRoutingHandler(rport,
                                                    invalidations ::= _,
                                                    dataClient,
                                                    config,
                                                    bgpd)))

        routingHandler ! rport
        bgpd.state should be (bgpd.NOT_STARTED)
        routingHandler ! RoutingHandler.Update(baseConfig, Set(peer1Id))
        sync()
        bgpd.state should be (bgpd.RUNNING)
        bgpd.starts should be (1)
    }

    after {
        as.stop(routingHandler)
        as.shutdown()
    }

    feature ("manages the bgpd lifecycle") {
        scenario("starts and stops bgpd") {
            routingHandler ! RoutingHandler.Update(baseConfig.copy(neighbors = Map.empty), Set.empty)
            sync()
            bgpd.state should be (bgpd.NOT_STARTED)

            routingHandler ! RoutingHandler.Update(baseConfig, Set(peer1Id))
            sync()
            bgpd.state should be (bgpd.RUNNING)
        }

        scenario("change in the router port address") {
            val p = rport.copy(true)
            p.portIp = IPv4Addr.fromString("192.168.80.2")
            p.afterFromProto(null)
            routingHandler ! p
            sync()
            bgpd.state should be (bgpd.RUNNING)
            bgpd.starts should be (2)

            routingHandler ! p.copy(true)
            sync()
            bgpd.state should be (bgpd.RUNNING)
            bgpd.starts should be (2)
        }

        scenario("port goes up and down") {
            routingHandler ! RoutingHandler.PortActive(false)
            sync()
            bgpd.state should be (bgpd.NOT_STARTED)

            routingHandler ! RoutingHandler.PortActive(true)
            sync()
            bgpd.state should be (bgpd.RUNNING)
        }

        scenario("zookepeer goes on and off") {
            routingHandler ! RoutingHandler.ZookeeperActive(false)
            sync()
            bgpd.state should be (bgpd.NOT_STARTED)

            routingHandler ! RoutingHandler.ZookeeperActive(true)
            sync()
            bgpd.state should be (bgpd.RUNNING)
        }

        scenario("zookeeper and the port go on and off") {
            routingHandler ! RoutingHandler.ZookeeperActive(false)
            sync()
            bgpd.state should be (bgpd.NOT_STARTED)

            routingHandler ! RoutingHandler.PortActive(false)
            routingHandler ! RoutingHandler.ZookeeperActive(true)
            sync()
            bgpd.state should be (bgpd.NOT_STARTED)

            routingHandler ! RoutingHandler.ZookeeperActive(false)
            routingHandler ! RoutingHandler.PortActive(true)
            sync()
            bgpd.state should be (bgpd.NOT_STARTED)

            routingHandler ! RoutingHandler.ZookeeperActive(true)
            sync()
            bgpd.state should be (bgpd.RUNNING)
        }

        scenario("bgp dies") {
            bgpd.die()
            routingHandler ! RoutingHandler.FETCH_BGPD_STATUS
            sync()
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
            sync()

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
            sync()

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
            sync()

            verify(dataClient).routesCreateEphemeral(argThat(matchRoute(dst1, gw)))
            verify(dataClient).routesCreateEphemeral(argThat(matchRoute(dst2, gw)))
            verify(dataClient).routesCreateEphemeral(argThat(matchRoute(dst3, gw)))

            reset(dataClient)
            routingHandler ! RoutingHandler.SYNC_PEER_ROUTES
            sync()

            verify(dataClient, times(2)).routesCreateEphemeral(anyObject())
            verify(dataClient).routesCreateEphemeral(argThat(matchRoute(dst1, gw)))
            verify(dataClient).routesCreateEphemeral(argThat(matchRoute(dst2, gw)))
        }
    }

    feature("reacts to changes in the bgp session configuration") {
        scenario("a new peer is added") {
        }

        scenario("a peer is removed") {
        }

        scenario("the last peer is deleted") {
        }

        scenario("routes are announced") {
        }

        scenario("routes are no longer announced") {
        }

        scenario("timer values change") {
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

    override def receive = {
        case latch: CountDownLatch =>
            belt.handle {
                () =>
                    latch.countDown()
                    Future.successful(true)
            }

        case m => super.receive(m)
    }

    override def unhookDpPort(port: NetDevPort): Future[_] = Future.successful(true)
    override def startZebra(): Unit = {}
    override def stopZebra(): Unit = {}
}
