/*
 * Copyright 2017 Midokura SARL
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

import java.io.{ByteArrayInputStream, ByteArrayOutputStream, DataInputStream, DataOutputStream}
import java.net.InetAddress
import java.nio.ByteBuffer

import org.junit.runner.RunWith
import org.midonet.netlink.rtnetlink.Link.Type.ARPHRD_ETHER
import org.midonet.packets.{IPv4Addr, IPv4Subnet, MAC}
import org.midonet.quagga.ZebraProtocol._
import org.scalatest.{BeforeAndAfter, FeatureSpecLike, Matchers}
import org.scalatest.junit.JUnitRunner

sealed class TestZebraHandler extends ZebraProtocolHandler {

    val routes = collection.mutable.Map[IPv4Subnet, Set[ZebraPath]]()

    override def addRoutes(destination: IPv4Subnet,
                           paths: Set[ZebraPath]): Unit = {
        routes(destination) = paths
    }

    override def removeRoute(ribType: RIBType.Value, destination: IPv4Subnet,
                             gateway: IPv4Addr): Unit = {
        routes.remove(destination)
    }
}

abstract class ZebraProtocolTest extends FeatureSpecLike with BeforeAndAfter with Matchers {

    def protocol: ZebraProtocol

    protected val interfaceAddress = IPv4Addr(InetAddress.getLocalHost.getAddress)
    protected val interfaceName = "zebra-test"
    protected val clientId = 1

    protected var handler: TestZebraHandler = _
    protected var in: ByteArrayInputStream = _
    protected var out: ByteArrayOutputStream = _
    protected var ctx: ZebraContext = _

    before {
        handler = new TestZebraHandler
    }

    protected def handleMessage(data: ByteBuffer): ByteBuffer = {
        in = new ByteArrayInputStream(data.array())
        out = new ByteArrayOutputStream(64)
        ctx = ZebraContext(interfaceAddress, interfaceName, clientId, handler,
            new DataInputStream(in), new DataOutputStream(out))
        ZebraProtocol.handleMessage(ctx)
        ByteBuffer.wrap(out.toByteArray)
    }

    protected def header(message: Short, length: Int = 0) = {
        val totalLength = protocol.headerSize + length
        val data = ByteBuffer.allocate(totalLength)
        data.putShort(totalLength.toShort)
        data.put(ZebraHeaderMarker.toByte)
        data.put(protocol.version.toByte)
        if (protocol.version == 3) data.putShort(ZebraDefaultVrf.toShort)
        data.putShort(message)
        data
    }

    private def getStringFromBuffer(data: ByteBuffer,
                                    length: Int = InterfaceNameSize) = {
        val buf = new Array[Byte](length)
        data.get(buf)
        new String(buf takeWhile (_ != 0)) // wrapped to 20 bytes
    }

    private def getMacFromBuffer(data: ByteBuffer) = {
        val buf = new Array[Byte](MacAddrLength)
        data.get(buf)
        MAC.fromAddress(buf)
    }

    feature(s"support Zebra V${protocol.version} messages") {
        scenario("respond to a ZEBRA_HELLO message") {
            val data = header(ZebraHello, 1)
            data.put(ZebraRouteBgp.toByte)

            val response = handleMessage(data)
            // Message was fully read
            in.available() shouldBe 0
            // We don't respond back
            response.capacity() shouldBe 0
        }

        scenario("respond to a ZEBRA_IPV4_ROUTE_ADD message") {
            val subnet = IPv4Subnet.fromCidr("192.168.0.0/30")
            val hops = Seq(IPv4Addr.random)

            val data = header(ZebraIpv4RouteAdd, 11 + 5 * hops.size)
            data.put(ZebraRouteBgp.toByte)
            data.put(0.toByte) // flags
            data.put(ZAPIMessageNextHop.toByte)
            data.putShort(0.toShort) // safi
            data.put(subnet.getPrefixLen.toByte) // prefixLength
            data.putInt(subnet.getIntAddress)
            data.put(hops.size.toByte) // # next hops

            for (hop <- hops) {
                data.put(ZebraNextHopIpv4.toByte)
                data.putInt(hop.addr)
            }

            val response = handleMessage(data)
            // Message was fully read
            in.available() shouldBe 0
            // We don't respond back
            response.capacity() shouldBe 0

            // Check all routes were added
            handler.routes.size shouldBe 1
            handler.routes.get(subnet).isDefined shouldBe true
            handler.routes(subnet).foreach { zPath =>
                zPath.distance shouldBe 1
                hops.contains(zPath.gateway) shouldBe true
            }
        }

        scenario("respond to a ZEBRA_IPV4_ROUTE_DELETE message") {
            val subnet = IPv4Subnet.fromCidr("192.168.0.0/30")
            handler.routes(subnet) = Set()
            val hops = Seq(IPv4Addr.random)

            val data = header(ZebraIpv4RouteDelete, 11 + 5 * hops.size)
            data.put(ZebraRouteBgp.toByte)
            data.put(0.toByte) // flags
            data.put(ZAPIMessageNextHop.toByte)
            data.putShort(0.toShort) // safi
            data.put(subnet.getPrefixLen.toByte) // prefixLength
            data.putInt(subnet.getIntAddress)
            data.put(hops.size.toByte) // # next hops

            for (hop <- hops) {
                data.put(ZebraNextHopIpv4.toByte)
                data.putInt(hop.addr)
            }

            val response = handleMessage(data)
            // Message was fully read
            in.available() shouldBe 0
            // We don't respond back
            response.capacity() shouldBe 0

            // Check all routes were deleted
            handler.routes.size shouldBe 0
            handler.routes.get(subnet).isDefined shouldBe false
        }

        scenario("respond to a ZEBRA_ROUTER_ID_ADD message") {
            val data = header(ZebraRouterIdAdd)

            val response = handleMessage(data)
            // Message was fully read
            in.available() shouldBe 0
            // We respond with an update back
            val expectedLength = protocol.headerSize +
                protocol.messageSizes(ZebraRouterIdUpdate)
            response.capacity() shouldBe expectedLength

            // Proper header
            response.getShort shouldBe expectedLength
            response.get shouldBe ZebraHeaderMarker.toByte
            response.get shouldBe protocol.version
            if (protocol.version == 3)
                response.getShort shouldBe ZebraDefaultVrf
            response.getShort shouldBe ZebraRouterIdUpdate

            // Proper update message
            response.get shouldBe AF_INET
            response.getInt shouldBe interfaceAddress.addr
            response.get shouldBe 32

            // Nothing left to read
            response.position() shouldBe response.capacity()
        }

        scenario("respond to a ZEBRA_INTERFACE_ADD message") {
            val data = header(ZebraInterfaceAdd)

            val response = handleMessage(data)
            // Message was fully read
            in.available() shouldBe 0

            // We respond with an interface add back and an interface address add
            val interfaceAddLength = protocol.headerSize +
                protocol.messageSizes(ZebraInterfaceAdd)
            val interfaceAddressAddLength = protocol.headerSize +
                protocol.messageSizes(ZebraInterfaceAddressAdd)
            response.capacity() shouldBe interfaceAddLength + interfaceAddressAddLength

            // Proper interface add header
            response.getShort shouldBe interfaceAddLength
            response.get shouldBe ZebraHeaderMarker.toByte
            response.get shouldBe protocol.version
            if (protocol.version == 3)
                response.getShort shouldBe ZebraDefaultVrf
            response.getShort shouldBe ZebraInterfaceAdd

            // Proper interface add message
            getStringFromBuffer(response) shouldBe interfaceName
            response.getInt shouldBe 0 // ifIndex
            response.get shouldBe ZebraInterfaceActive // status
            response.getLong shouldBe (IFF_UP | IFF_BROADCAST | IFF_PROMISC | IFF_MULTICAST)
            response.getInt shouldBe 1 // metric
            response.getInt shouldBe ZebraConnection.MidolmanMTU // IPv4 MTU
            response.getInt shouldBe ZebraConnection.MidolmanMTU // IPv6 MTU
            response.getInt shouldBe 0 // bandwidth
            if (protocol.version == 3)
                response.getInt shouldBe ARPHRD_ETHER
            response.getInt shouldBe MacAddrLength
            getMacFromBuffer(response) shouldBe MAC.ALL_ZEROS
            if (protocol.version == 3)
                response.get shouldBe 0 // link params status

            // Proper interface address add header
            response.getShort shouldBe interfaceAddressAddLength
            response.get shouldBe ZebraHeaderMarker.toByte
            response.get shouldBe protocol.version
            if (protocol.version == 3)
                response.getShort shouldBe ZebraDefaultVrf
            response.getShort shouldBe ZebraInterfaceAddressAdd

            // Proper interface address add message
            response.getInt shouldBe 0 // ifIndex
            response.get shouldBe 0 // ifAddress flags
            response.get shouldBe AF_INET
            response.getInt shouldBe interfaceAddress.addr
            response.get shouldBe 4 // ifAddress length
            response.getInt shouldBe 0 // dstAddress

            // Nothing left to read
            response.position() shouldBe response.capacity()
        }

        scenario("respond to a ZEBRA_NEXTHOP_LOOKUP message") {
            val address = IPv4Addr.random
            val data = header(ZebraIpv4NextHopLookup, 4)
            data.putInt(address.addr)

            val response = handleMessage(data)
            // Message was fully read
            in.available() shouldBe 0
            // We respond with a nexthop lookup back
            val expectedLength = protocol.headerSize +
                protocol.messageSizes(ZebraIpv4NextHopLookup)
            response.capacity() shouldBe expectedLength

            // Proper header
            response.getShort shouldBe expectedLength
            response.get shouldBe ZebraHeaderMarker.toByte
            response.get shouldBe protocol.version
            if (protocol.version == 3)
                response.getShort shouldBe ZebraDefaultVrf
            response.getShort shouldBe ZebraIpv4NextHopLookup

            // Proper update message
            response.getInt shouldBe address.addr
            response.getInt shouldBe 1 // metric
            response.get shouldBe 1 // # nexthops
            response.get shouldBe ZebraNextHopIpv4
            response.getInt shouldBe address.addr

            // Nothing left to read
            response.position() shouldBe response.capacity()
        }

        scenario("fail to a ZEBRA_HELLO message with incorrect type") {
            val data = header(ZebraHello, 1)
            data.put(ZebraRouteRip.toByte)

            an [AssertionError] shouldBe thrownBy {
                handleMessage(data)
            }

            // Message was fully read
            in.available() shouldBe 0
        }

        scenario("reads unsupported messages without failing") {
            val unsupported = Seq(ZebraIpv6RouteAdd, ZebraIpv6RouteDelete,
                ZebraIpv6ImportLookup, ZebraIpv6NextHopLookup)

            for (msg <- unsupported) handleMessage(header(msg))

            // Messages were fully read
            in.available() shouldBe 0
        }

        scenario("fails on unknown messages") {
            val unknown = Seq(-1, 123, 500).map(_.toShort)

            for (msg <- unknown) {
                a [NoSuchElementException] shouldBe thrownBy {
                    handleMessage(header(msg))
                }
            }

            // Messages were fully read
            in.available() shouldBe 0
        }
    }
}

@RunWith(classOf[JUnitRunner])
class ZebraProtocolV2Test extends ZebraProtocolTest {
    override def protocol: ZebraProtocol = ZebraProtocolV2
}

@RunWith(classOf[JUnitRunner])
class ZebraProtocolV3Test extends ZebraProtocolTest {
    override def protocol: ZebraProtocol = ZebraProtocolV3

    feature("support Zebra V3-only messages") {
        scenario("respond to a ZEBRA_NEXTHOP_REGISTER message") {
            val address = IPv4Addr.random
            val data = header(ZebraNextHopRegister, 8)
            data.put(1.toByte) // nexthop connected
            data.putShort(AF_INET.toShort) // prefix family
            data.put(32.toByte) // prefix length
            data.putInt(address.addr)

            val response = handleMessage(data)
            // Message was fully read
            in.available() shouldBe 0
            // We respond with a nexthop update
            val expectedLength = ZebraProtocolV3.headerSize +
                ZebraProtocolV3.messageSizes(ZebraNextHopUpdate)
            response.capacity() shouldBe expectedLength

            // Proper header
            response.getShort shouldBe expectedLength
            response.get shouldBe ZebraHeaderMarker.toByte
            response.get shouldBe protocol.version
            if (protocol.version == 3)
                response.getShort shouldBe ZebraDefaultVrf
            response.getShort shouldBe ZebraNextHopUpdate

            // Proper update message
            response.getShort shouldBe AF_INET
            response.get shouldBe 32
            response.getInt shouldBe address.addr
            response.getInt shouldBe 1 // interface metric
            response.get shouldBe 1 // # nexthops
            response.get shouldBe ZebraNextHopIpv4
            response.getInt shouldBe interfaceAddress.addr

            // Nothing left to read
            response.position() shouldBe response.capacity()
        }
    }
}