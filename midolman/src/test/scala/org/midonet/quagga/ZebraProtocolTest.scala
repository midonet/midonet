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
import org.midonet.packets.{IPv4Addr, IPv4Subnet, MAC}
import org.midonet.quagga.ZebraProtocol.{MacAddrLength, ZebraInterfaceAdd, ZebraIpv4RouteAdd, ZebraRouterIdUpdate, _}
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

@RunWith(classOf[JUnitRunner])
class ZebraProtocolTest extends FeatureSpecLike with BeforeAndAfter with Matchers {

    private val interfaceAddress = IPv4Addr(InetAddress.getLocalHost.getAddress)
    private val interfaceName = "zebra-test"
    private val clientId = 1

    private var handler: TestZebraHandler = _
    private var in: ByteArrayInputStream = _
    private var out: ByteArrayOutputStream = _
    private var ctx: ZebraContext = _

    before {
        handler = new TestZebraHandler
    }

    private def handleMessage(data: ByteBuffer): ByteBuffer = {
        in = new ByteArrayInputStream(data.array())
        out = new ByteArrayOutputStream(64)
        ctx = ZebraContext(interfaceAddress, interfaceName, clientId, handler,
            new DataInputStream(in), new DataOutputStream(out))
        ZebraProtocol.handleMessage(ctx)
        ByteBuffer.wrap(out.toByteArray)
    }

    private def v2Header(message: Short, length: Int = 0) = {
        val totalLength = ZebraProtocolV2.headerSize + length
        val data = ByteBuffer.allocate(totalLength)
        data.putShort(totalLength.toShort)
        data.put(ZebraHeaderMarker.toByte)
        data.put(ZebraProtocolV2.version.toByte)
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

    feature("support Zebra V2 messages") {
        scenario("respond to a ZEBRA_HELLO message") {
            val data = v2Header(ZebraHello, 1)
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

            val data = v2Header(ZebraIpv4RouteAdd, 11 + 5 * hops.size)
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

            val data = v2Header(ZebraIpv4RouteDelete, 11 + 5 * hops.size)
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
            val data = v2Header(ZebraRouterIdAdd)

            val response = handleMessage(data)
            // Message was fully read
            in.available() shouldBe 0
            // We respond with an update back
            val expectedLength = ZebraProtocolV2.headerSize +
                ZebraProtocolV2.messageSizes(ZebraRouterIdUpdate)
            response.capacity() shouldBe expectedLength

            // Proper header
            response.getShort shouldBe expectedLength
            response.get shouldBe ZebraHeaderMarker.toByte
            response.get shouldBe ZebraProtocolV2.version
            response.getShort shouldBe ZebraRouterIdUpdate

            // Proper update message
            response.get shouldBe AF_INET
            response.getInt shouldBe interfaceAddress.addr
            response.get shouldBe 32

            // Nothing left to read
            response.position() shouldBe response.capacity()
        }

        scenario("respond to a ZEBRA_INTERFACE_ADD message") {
            val data = v2Header(ZebraInterfaceAdd)

            val response = handleMessage(data)
            // Message was fully read
            in.available() shouldBe 0

            // We respond with an interface add back and an interface address add
            val interfaceAddLength = ZebraProtocolV2.headerSize +
                ZebraProtocolV2.messageSizes(ZebraInterfaceAdd)
            val interfaceAddressAddLength = ZebraProtocolV2.headerSize +
                ZebraProtocolV2.messageSizes(ZebraInterfaceAddressAdd)
            response.capacity() shouldBe interfaceAddLength + interfaceAddressAddLength

            // Proper interface add header
            response.getShort shouldBe interfaceAddLength
            response.get shouldBe ZebraHeaderMarker.toByte
            response.get shouldBe ZebraProtocolV2.version
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
            response.getInt shouldBe MacAddrLength
            getMacFromBuffer(response) shouldBe MAC.ALL_ZEROS

            // Proper interface address add header
            response.getShort shouldBe interfaceAddressAddLength
            response.get shouldBe ZebraHeaderMarker.toByte
            response.get shouldBe ZebraProtocolV2.version
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
            val data = v2Header(ZebraIpv4NextHopLookup, 4)
            data.putInt(address.addr)

            val response = handleMessage(data)
            // Message was fully read
            in.available() shouldBe 0
            // We respond with a nexthop lookup back
            val expectedLength = ZebraProtocolV2.headerSize + ZebraProtocolV2.messageSizes(ZebraIpv4NextHopLookup)
            response.capacity() shouldBe expectedLength

            // Proper header
            response.getShort shouldBe expectedLength
            response.get shouldBe ZebraHeaderMarker.toByte
            response.get shouldBe ZebraProtocolV2.version
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
            val data = v2Header(ZebraHello, 1)
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

            for (msg <- unsupported) handleMessage(v2Header(msg))

            // Messages were fully read
            in.available() shouldBe 0
        }

        scenario("fails on unknown messages") {
            val unknown = Seq(-1, 123, 500).map(_.toShort)

            for (msg <- unknown) {
                a [NoSuchElementException] shouldBe thrownBy {
                    handleMessage(v2Header(msg))
                }
            }

            // Messages were fully read
            in.available() shouldBe 0
        }
    }
}
