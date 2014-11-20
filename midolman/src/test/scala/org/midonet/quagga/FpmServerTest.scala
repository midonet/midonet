/*
 * Copyright 2014 Midokura SARL
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


import akka.actor.ActorRef
import com.typesafe.scalalogging.Logger
import org.jboss.netty.buffer.{ChannelBuffers, ChannelBuffer}
import org.midonet.packets.{IPv4Subnet, IPv4Addr}
import org.scalatest.{Matchers, FunSuite, BeforeAndAfter}
import org.scalatest.concurrent.Eventually
import org.slf4j.LoggerFactory


class FpmServerTest extends FunSuite
    with BeforeAndAfter
    with Eventually
    with Matchers {

    val ADD = 1
    val REM = 2

    case class Route(destAddr: IPv4Subnet, gatewayAddr: IPv4Addr)

    case class RouteBuilder(destAddr: IPv4Subnet, gatewayAddr: IPv4Addr,
                            AF: Int, netlinkMsgLen: Int)

    val routes = scala.collection.mutable.Set[Route]()

    class MockFpmServerHandler(override val routingHandler: ActorRef,
                               override val fpmServer: ActorRef,
                               override val port: Int,
                               override val logger: Logger)
        extends FpmServerHandler (routingHandler, fpmServer, port, logger) {

        override def addZebraRoute(dest: IPv4Subnet, gate: IPv4Addr): Unit = {
            routes.add(Route(dest, gate))
        }
        override def remZebraRoute(dest: IPv4Subnet): Unit = {
            var zebraRoute: Route = null
            for (route <- routes) {
                if (route.destAddr == dest) {
                    zebraRoute = route
                }
            }
            routes.remove(Route(dest, zebraRoute.gatewayAddr))
        }
    }

    var fpmServer: MockFpmServerHandler = _

    def createAddPacket(routes: Set[RouteBuilder]): ChannelBuffer = {

        val chanBuf = ChannelBuffers.buffer(56*routes.size)
        for (route: RouteBuilder <- routes) {

            val sub = route.destAddr
            val gate = route.gatewayAddr

            val FPMHeader = Array[Byte](0x01, 0x01, 0x10, 0x10)
            chanBuf.writeBytes(FPMHeader)

            val nlHeader = Array[Byte](
                0x00, 0x00, 0x00, route.netlinkMsgLen.toByte, //netlink length
                0x00, 0x18, // netlink msg type
                0x00, 0x00, // netlink flags
                0x00, 0x00, 0x00, 0x00, // netlink seq
                0x00, 0x00, 0x00, 0x00) // netlink pid
            chanBuf.writeBytes(nlHeader)

            val rtHeader = Array[Byte](
                route.AF.toByte, // route family
                sub.getPrefixLen.toByte, // route dest len
                0x00, // route source len
                0x00, // route TOS
                0x00, // route table
                0x0B, // route protocol
                0x0B, // route scope
                0x00, // route type
                0x00, 0x00, 0x00, 0x00) // route flags
            chanBuf.writeBytes(rtHeader)

            val attr1 = Array[Byte](
                0x00, 0x08, // attr length
                0x00, 0x01) // attr type
            chanBuf.writeBytes(attr1)
            chanBuf.writeBytes(sub.getAddress.toBytes) // address

            val attr2 = Array[Byte](
                0x00, 0x08, // attr length
                0x00, 0x05) // attr type
            chanBuf.writeBytes(attr2)
            chanBuf.writeBytes(gate.toBytes) // address

            val attr3 = Array[Byte](
                0x00, 0x08, // attr length
                0x00, 0x02, // attr type
                0xC0.toByte, 0xA8.toByte, 0x00, 0x05) // address
            chanBuf.writeBytes(attr3)
        }
        chanBuf
    }

    def createRemPacket(routes: Set[RouteBuilder]): ChannelBuffer = {

        val chanBuf = ChannelBuffers.buffer(48*routes.size)
        for (route: RouteBuilder <- routes) {

            val sub = route.destAddr
            val gate = route.gatewayAddr

            val FPMHeader = Array[Byte](0x01, 0x01, 0x10, 0x10)
            chanBuf.writeBytes(FPMHeader)

            val nlHeader = Array[Byte](
                0x00, 0x00, 0x00, route.netlinkMsgLen.toByte, //netlink length
                0x00, 0x19, // netlink msg type
                0x00, 0x00, // netlink flags
                0x00, 0x00, 0x00, 0x00, // netlink seq
                0x00, 0x00, 0x00, 0x00) // netlink pid
            chanBuf.writeBytes(nlHeader)

            val rtHeader = Array[Byte](
                route.AF.toByte, // route family
                sub.getPrefixLen.toByte, // route dest len
                0x00, // route source len
                0x00, // route TOS
                0x00, // route table
                0x00, // route protocol
                0x0B, // route scope
                0x00, // route type
                0x00, 0x00, 0x00, 0x00) // route flags
            chanBuf.writeBytes(rtHeader)

            val attr1 = Array[Byte](
                0x00, 0x08, // attr length
                0x00, 0x01) // attr type
            chanBuf.writeBytes(attr1)
            chanBuf.writeBytes(sub.getAddress.toBytes) // address

            val attr3 = Array[Byte](
                0x00, 0x08, // attr length
                0x00, 0x02, // attr type
                0xC0.toByte, 0xA8.toByte, 0x00, 0x05) // address
            chanBuf.writeBytes(attr3)
        }
        chanBuf
    }

    before {
        fpmServer = new MockFpmServerHandler(null, null, 123,
            Logger(LoggerFactory.getLogger("blah")))
        routes.clear()
    }

    test("FpmServer creation") {
        assert(fpmServer != null)
    }

    test("Single route add")
    {
        val dest = new IPv4Subnet("192.168.0.5", 24)
        val gateway = IPv4Addr.fromString("10.0.0.5")
        val chanBuf = createAddPacket(Set(RouteBuilder(dest, gateway, 2, 52)))
        fpmServer.processFPMPacket(chanBuf)
        assert(chanBuf.readableBytes() == 0)
        assert(routes.contains(Route(dest, gateway)))
        routes.clear()
    }

    test("Multiple route adds followed by removals")
    {
        var dest = new IPv4Subnet("192.168.0.5", 24)
        var gateway = IPv4Addr.fromString("10.0.0.5")
        var chanBuf = createAddPacket(Set(RouteBuilder(dest, gateway, 2, 52)))
        fpmServer.processFPMPacket(chanBuf)
        assert(chanBuf.readableBytes() == 0)
        assert(routes.contains(Route(dest, gateway)))
        assert(routes.size == 1)

        dest = new IPv4Subnet("192.168.0.6", 24)
        gateway = IPv4Addr.fromString("10.0.0.5")
        chanBuf = createAddPacket(Set(RouteBuilder(dest, gateway, 2, 52)))
        fpmServer.processFPMPacket(chanBuf)
        assert(chanBuf.readableBytes() == 0)
        assert(routes.contains(Route(dest, gateway)))
        assert(routes.size == 2)

        dest = new IPv4Subnet("192.168.0.7", 24)
        gateway = IPv4Addr.fromString("10.0.0.5")
        chanBuf = createAddPacket(Set(RouteBuilder(dest, gateway, 2, 52)))
        fpmServer.processFPMPacket(chanBuf)
        assert(chanBuf.readableBytes() == 0)
        assert(routes.contains(Route(dest, gateway)))
        assert(routes.size == 3)

        chanBuf = createRemPacket(Set(RouteBuilder(dest, gateway, 2, 44)))
        fpmServer.processFPMPacket(chanBuf)
        assert(chanBuf.readableBytes() == 0)
        assert(!routes.contains(Route(dest, gateway)))
        assert(routes.size == 2)

        dest = new IPv4Subnet("192.168.0.6", 24)
        gateway = IPv4Addr.fromString("10.0.0.5")
        chanBuf = createRemPacket(Set(RouteBuilder(dest, gateway, 2, 44)))
        fpmServer.processFPMPacket(chanBuf)
        assert(chanBuf.readableBytes() == 0)
        assert(!routes.contains(Route(dest, gateway)))
        assert(routes.size == 1)

        dest = new IPv4Subnet("192.168.0.5", 24)
        gateway = IPv4Addr.fromString("10.0.0.5")
        chanBuf = createRemPacket(Set(RouteBuilder(dest, gateway, 2, 44)))
        fpmServer.processFPMPacket(chanBuf)
        assert(chanBuf.readableBytes() == 0)
        assert(!routes.contains(Route(dest, gateway)))
        assert(routes.size == 0)
    }

    test("One large add followed by multiple removals")
    {
        var dest = new IPv4Subnet("192.168.0.5", 24)
        var gateway = IPv4Addr.fromString("10.0.0.5")
        var routeSet = Set(RouteBuilder(dest, gateway, 2, 52))
        dest = new IPv4Subnet("192.168.0.6", 24)
        gateway = IPv4Addr.fromString("10.0.0.5")
        routeSet += RouteBuilder(dest, gateway, 2, 52)
        dest = new IPv4Subnet("192.168.0.7", 24)
        gateway = IPv4Addr.fromString("10.0.0.5")
        routeSet += RouteBuilder(dest, gateway, 2, 52)
        var chanBuf = createAddPacket(routeSet)
        fpmServer.processFPMPacket(chanBuf)
        assert(chanBuf.readableBytes() == 0)
        assert(routes.contains(Route(dest, gateway)))
        assert(routes.size == 3)

        dest = new IPv4Subnet("192.168.0.7", 24)
        gateway = IPv4Addr.fromString("10.0.0.5")
        chanBuf = createRemPacket(Set(RouteBuilder(dest, gateway, 2, 44)))
        fpmServer.processFPMPacket(chanBuf)
        assert(chanBuf.readableBytes() == 0)
        assert(!routes.contains(Route(dest, gateway)))
        assert(routes.size == 2)

        dest = new IPv4Subnet("192.168.0.6", 24)
        gateway = IPv4Addr.fromString("10.0.0.5")
        chanBuf = createRemPacket(Set(RouteBuilder(dest, gateway, 2, 44)))
        fpmServer.processFPMPacket(chanBuf)
        assert(chanBuf.readableBytes() == 0)
        assert(!routes.contains(Route(dest, gateway)))
        assert(routes.size == 1)

        dest = new IPv4Subnet("192.168.0.5", 24)
        gateway = IPv4Addr.fromString("10.0.0.5")
        chanBuf = createRemPacket(Set(RouteBuilder(dest, gateway, 2, 44)))
        fpmServer.processFPMPacket(chanBuf)
        assert(chanBuf.readableBytes() == 0)
        assert(!routes.contains(Route(dest, gateway)))
        assert(routes.size == 0)
    }

    test("Multiple routes incoming, one of them is IPv6")
    {
        var dest = new IPv4Subnet("192.168.0.5", 24)
        var gateway = IPv4Addr.fromString("10.0.0.5")
        var routeSet = Set(RouteBuilder(dest, gateway, 4, 52))
        dest = new IPv4Subnet("192.168.0.6", 24)
        gateway = IPv4Addr.fromString("10.0.0.5")
        routeSet += RouteBuilder(dest, gateway, 2, 52)
        dest = new IPv4Subnet("192.168.0.7", 24)
        gateway = IPv4Addr.fromString("10.0.0.5")
        routeSet += RouteBuilder(dest, gateway, 2, 52)
        val chanBuf = createAddPacket(routeSet)
        fpmServer.processFPMPacket(chanBuf)
        assert(chanBuf.readableBytes() == 0)
        assert(routes.contains(Route(dest, gateway)))
        assert(routes.contains(Route(new IPv4Subnet("192.168.0.7", 24),
                                     IPv4Addr.fromString("10.0.0.5"))))
        assert(routes.size == 2)
    }

    test("If the length is invalid, assertion should be thrown")
    {
        var dest = new IPv4Subnet("192.168.0.5", 24)
        var gateway = IPv4Addr.fromString("10.0.0.5")
        var routeSet = Set(RouteBuilder(dest, gateway, 4, 52))
        dest = new IPv4Subnet("192.168.0.6", 24)
        gateway = IPv4Addr.fromString("10.0.0.5")
        routeSet += RouteBuilder(dest, gateway, 2, 51)
        dest = new IPv4Subnet("192.168.0.7", 24)
        gateway = IPv4Addr.fromString("10.0.0.5")
        routeSet += RouteBuilder(dest, gateway, 2, 52)
        val chanBuf = createAddPacket(routeSet)
        intercept[AssertionError]
        {
            fpmServer.processFPMPacket(chanBuf)
        }
    }
}
