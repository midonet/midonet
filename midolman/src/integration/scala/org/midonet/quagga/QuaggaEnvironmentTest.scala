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
package org.midonet.quagga

import akka.actor.{ActorSystem, ActorRef, Actor}
import akka.testkit.{TestKit, TestActorRef}
import com.typesafe.scalalogging.Logger
import org.junit.runner.RunWith
import org.midonet.midolman.routingprotocols.RoutingHandler
import org.scalatest._
import org.scalatest.junit.JUnitRunner
import org.scalatest.concurrent.Eventually._

import org.midonet.packets.{MAC, IPv4Addr, IPv4Subnet}
import org.scalatest.time.SpanSugar
import org.slf4j.LoggerFactory

/*
 * This tests the FPM server functionality with an actual quagga environment.
 *
 *                           +------------------------+
 *    +-----------+          | +-------+   +------+   |
 *    |           |          | |       |   |      |   |
 *    | FpmServer +<-----------+ ZEBRA +---+ BGPD |   |
 *    |           | FPM push | |       |   |      |   |
 *    +-----------+          | +-------+   +--+---+   |
 *                           |                ^       |
 *                           |  quagga        |       |
 *                           +----------------|-------+
 *                                            |
 *                         route advertisement|
 *                                            |
 *                           +----------------|-------+
 *                           |                |       |
 *                           |             +--+---+   |
 *                           |             |      |   |
 *       addNetwork+---------------------->| BGPD |   |
 *                           |             |      |   |
 *                           |             +------+   |
 *                           | peerQuagga             |
 *                           +------------------------+
 *
 * In fact, it uses 2 quagga environments: one to interact with midonet,
 * and one to act as the first quagga's peer. The test works by setting up
 * the above environment, then adding and removing routes on the peerQuagga
 * environment. We then make checks to see that those routes were advertised
 * to the other quagga environment, then verifying that the route information
 * is pushed to the FpmServer.
 */
@RunWith(classOf[JUnitRunner])
class QuaggaEnvironmentTest extends TestKit(ActorSystem("QuaggaEnvironment"))
        with Suite with BeforeAndAfter with ShouldMatchers with SpanSugar {

    case class Route(destAddr: IPv4Subnet, gatewayAddr: IPv4Addr)

    /*
     * This set will keep track of the routes that we have learned from
     * the zebra server, both removals and additions.
     */
    val routes = scala.collection.mutable.Set[Route]()

    /* The FpmServer sends all of its route updates to an actor. In practice
     * this will be the RoutingHandler, but for just testing we need something
     * that will update 'routes' and no more.
     */
    class TestFpmUpdateReceiver(val logger: Logger) extends Actor {
        override def receive() = {
            case RoutingHandler.AddZebraPeerRoute(destination, gateway) =>
                routes.add(Route(destination, gateway))
            case RoutingHandler.RemoveZebraPeerRoute(destination, gateway) =>
                routes.remove(Route(destination, gateway))
            case m =>
        }
    }

    // Midonets Quagga Environment settings.
    val idx = 6
    val PREFIX = 172 * (1<<24) + 23 * (1<<16)
    val BGP_VTY_LOCAL_IP = new IPv4Subnet(IPv4Addr.fromInt(PREFIX + 1 + 4 * idx), 30)
    val BGP_VTY_MIRROR_IP = new IPv4Subnet(IPv4Addr.fromInt(PREFIX + 2 + 4 * idx), 30)
    val BGP_VTY_PORT = 2605 + idx

    // The peer quagga environment. Does not speak to Midonet.
    val peerIdx = 7
    val PEER_PREFIX = 172 * (1<<24) + 24 * (1<<16)
    val PEER_BGP_VTY_LOCAL_IP = new IPv4Subnet(IPv4Addr.fromInt(PEER_PREFIX + 1 + 4 * peerIdx), 30)
    val PEER_BGP_VTY_MIRROR_IP = new IPv4Subnet(IPv4Addr.fromInt(PEER_PREFIX + 2 + 4 * peerIdx), 30)
    val PEER_BGP_VTY_PORT = 2605 + peerIdx

    val routerAddress = IPv4Subnet.fromCidr("192.168.163.1/24")
    val routerMac = MAC.random()

    var quaggaEnv: QuaggaEnvironment = _
    var peerQuaggaEnv: QuaggaEnvironment = _

    var fpmupdateReceiver: ActorRef = _
    var fpmServer: FpmServer = _

    implicit def str2subnet(str: String) = IPv4Subnet.fromCidr(str)
    implicit def str2ipv4(str: String) = IPv4Addr.fromString(str)

    before {
        quaggaEnv = new DefaultQuaggaEnvironment(idx,
            BGP_VTY_LOCAL_IP, BGP_VTY_MIRROR_IP,
            routerAddress, routerMac, BGP_VTY_PORT,
            "/home/joe/midonet/midolman/src/lib/midolman/bgpd-helper")

        peerQuaggaEnv = new DefaultQuaggaEnvironment(peerIdx,
            PEER_BGP_VTY_LOCAL_IP, PEER_BGP_VTY_MIRROR_IP,
            routerAddress, routerMac, PEER_BGP_VTY_PORT,
            "/home/joe/midonet/midolman/src/lib/midolman/bgpd-helper")

        fpmupdateReceiver = TestActorRef(new TestFpmUpdateReceiver(
            Logger(LoggerFactory.getLogger("blah"))))

        fpmServer = new FpmServer(2620+idx, fpmupdateReceiver,
                                  Logger(LoggerFactory.getLogger("fpm")))
    }

    after {
        if (quaggaEnv ne null)
            quaggaEnv.stop()
        if (peerQuaggaEnv ne null)
            peerQuaggaEnv.stop()
    }

    /*
     * This test is simple in that it just tests 2 route additions and
     * 2 route removals, but it actually tests quite a lot because this
     * small use case uses most of the relevant features.
     */
    def testZebraRouteAdditionsAndRemovals(): Unit = {

        // Start the Fpm Server
        // (The server is on the midonet side. The client is Zebra.)
        fpmServer.setupConnection()

        // Start the quagga environment that will push updates to midonet.
        quaggaEnv.prepare()
        quaggaEnv.start()

        // Start the quagga environment that will peer.
        peerQuaggaEnv.prepare()
        peerQuaggaEnv.start()

        quaggaEnv.bgpVty.setAs(23)
        quaggaEnv.bgpVty.setRouterId(23, BGP_VTY_MIRROR_IP.getAddress)
        quaggaEnv.bgpVty.addPeer(23, PEER_BGP_VTY_MIRROR_IP.getAddress, 24, 1, 2, 3)
        quaggaEnv.bgpVty.setPeerAdInterval(23, PEER_BGP_VTY_MIRROR_IP.getAddress, 1)
        quaggaEnv.bgpVty.setPeerMultihop(23, PEER_BGP_VTY_MIRROR_IP.getAddress, 255)

        peerQuaggaEnv.bgpVty.setAs(24)
        peerQuaggaEnv.bgpVty.setRouterId(24, PEER_BGP_VTY_MIRROR_IP.getAddress)
        peerQuaggaEnv.bgpVty.addPeer(24, BGP_VTY_MIRROR_IP.getAddress, 23, 1, 2, 3)
        peerQuaggaEnv.bgpVty.setPeerAdInterval(24, BGP_VTY_MIRROR_IP.getAddress, 1)
        peerQuaggaEnv.bgpVty.setPeerMultihop(24, BGP_VTY_MIRROR_IP.getAddress, 255)

        val sub1 = IPv4Subnet.fromCidr("10.0.10.0/24")
        val sub2 = IPv4Subnet.fromCidr("10.0.11.0/24")

        routes.size shouldBe 0

        peerQuaggaEnv.bgpVty.addNetwork(24, sub1)

        /*
         * Why so much waiting? BGP does not push updates about its
         * routes very often. You can't configure the pushes to happen
         * in less than a second, and there is an additional delay
         * when the environments first start up.
         */

        val ito = timeout(10 seconds)
        val to = timeout(2 seconds)
        val i = interval(100 milliseconds)

        eventually(ito, i) { routes.size shouldBe 1 }

        peerQuaggaEnv.bgpVty.addNetwork(24, sub2)

        eventually(to, i) { routes.size shouldBe 2 }

        peerQuaggaEnv.bgpVty.deleteNetwork(24, sub1)

        eventually(to, i) { routes.size shouldBe 1 }

        peerQuaggaEnv.bgpVty.deleteNetwork(24, sub2)

        eventually(to, i) {routes.size shouldBe 0}

        fpmServer.destroyConnection()
        if (quaggaEnv ne null)
            quaggaEnv.stop()
        if (peerQuaggaEnv ne null)
            peerQuaggaEnv.stop()
    }
}
