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

import java.io.DataOutputStream

import org.midonet.quagga.ZebraProtocol._
import org.midonet.quagga.ZebraProtocolMessages._
import org.slf4j.LoggerFactory

trait ZebraProtocol {

    def version: Int
    def headerSize: Int
    def messageNames: Map[Short, String]
    def messageSizes: Map[Short, Byte]

    /*
     * look at zserv.c from quagga (zserv_create_header)
     */
    def sendHeader(out: DataOutputStream, message: Short): Unit
    def handleMessage(message: Short, ctx: ZebraContext): Unit

    protected def unsupported(message: Short) = {
        val msg = messageNames.getOrElse(message, "unrecognized-message")
        log.warn(s"$msg isn't implemented yet")
    }
}

object ZebraProtocol {

    final val log = LoggerFactory.getLogger("org.midonet.routing.bgp.zebra")

    object RIBType extends Enumeration {
        type RIBType = Value
        val RIP = Value("RIP")
        val OSPF = Value("OSPF")
        val ISIS = Value("ISIS")
        val BGP = Value("BGP")

        def fromInteger(ribType: Int): RIBType.Value = {
            ribType match {
                case ZebraRouteRip => RIP
                case ZebraRouteBgp => BGP
                case ZebraRouteOspf => OSPF
                case ZebraRouteIsis => ISIS
            }
        }
    }

    // IP constants.
    final val AF_INET = 2
    final val AF_INET6 = 10
    final val Ipv4MaxBytelen = 4
    final val Ipv6MaxBytelen = 16

    // Zebra protocol headers.
    // https://github.com/Quagga/quagga/blob/master/lib/vrf.h#L29
    final val ZebraDefaultVrf = 0
    final val ZebraHeaderMarker = 255
    final val ZebraMaxPayloadSize = (1 << 16) - 1

    // Zebra message types.
    final val ZebraInterfaceAdd: Short = 1
    final val ZebraInterfaceDelete: Short = 2
    final val ZebraInterfaceAddressAdd: Short = 3
    final val ZebraInterfaceAddressDelete: Short = 4
    final val ZebraInterfaceUp: Short = 5
    final val ZebraInterfaceDown: Short = 6
    final val ZebraIpv4RouteAdd: Short = 7
    final val ZebraIpv4RouteDelete: Short = 8
    final val ZebraIpv6RouteAdd: Short = 9
    final val ZebraIpv6RouteDelete: Short = 10
    final val ZebraRedistributeAdd: Short = 11
    final val ZebraRedistributeDelete: Short = 12
    final val ZebraRedistributeDefaultAdd: Short = 13
    final val ZebraRedistributeDefaultDelete: Short = 14
    final val ZebraIpv4NextHopLookup: Short = 15
    final val ZebraIpv6NextHopLookup: Short = 16
    final val ZebraIpv4ImportLookup: Short = 17
    final val ZebraIpv6ImportLookup: Short = 18
    final val ZebraInterfaceRename: Short = 19
    final val ZebraRouterIdAdd: Short = 20
    final val ZebraRouterIdDelete: Short = 21
    final val ZebraRouterIdUpdate: Short = 22
    final val ZebraHello: Short = 23

    // New for zebra v3
    final val ZebraIpv4NexthopLookupMrib: Short = 24
    final val ZebraVrfUnregister: Short = 25
    final val ZebraInterfaceLinkParams: Short = 26
    final val ZebraNextHopRegister: Short = 27
    final val ZebraNextHopUnregister: Short = 28
    final val ZebraNextHopUpdate: Short = 29

    // Interface related constants.
    final val InterfaceNameSize = 20
    final val InterfaceHwAddrMax = 20
    final val MacAddrLength = 6
    final val ZebraInterfaceActive = 1 << 0
    final val ZebraInterfaceSub = 1 << 1
    final val ZebraInterfaceLinkdetection = 1 << 2

    // c.f. /usr/include/net/if.h
    final val IFF_UP = 1 << 0
    final val IFF_BROADCAST = 1 << 1
    final val IFF_DEBUG = 1 << 2
    final val IFF_LOOPBACK = 1 << 3
    final val IFF_POINTOPOINT = 1 << 4
    final val IFF_NOTRAILERS = 1 << 5
    final val IFF_RUNNING = 1 << 6
    final val IFF_NOARP = 1 << 7
    final val IFF_PROMISC = 1 << 8
    final val IFF_ALLMULTI = 1 << 9
    final val IFF_MASTER = 1 << 10
    final val IFF_SLAVE = 1 << 11
    final val IFF_MULTICAST = 1 << 12
    final val IFF_PORTSEL = 1 << 13
    final val IFF_AUTOMEDIA = 1 << 14
    final val IFF_DYNAMIC = 1 << 15

    // Flags for connected address.
    final val ZEBRA_IFA_SECONDARY = 1 << 0
    final val ZEBRA_IFA_PEER = 1 << 1

    // Zebra IPv4 route message API.
    final val ZAPIMessageNextHop = 1 << 0
    final val ZAPIMessageIfIndex = 1 << 1
    final val ZAPIMessageDistance = 1 << 2
    final val ZAPIMessageMetric = 1 << 3

    // Zebra nexthop flags.
    final val ZebraNexthopIfIndex = 1
    final val ZebraNextHopIfName = 2
    final val ZebraNextHopIpv4 = 3
    final val ZebraNextHopIpv4IfIndex = 4
    final val ZebraNextHopIpv4IfName = 5
    final val ZebraNextHopIpv6 = 6
    final val ZebraNextHopIpv6IfIndex = 7
    final val ZebraNextHopIpv6IfName = 8
    final val ZebraNextHopBlackhole = 9

    // Zebra route types.
    final val ZebraRouteSystem = 0
    final val ZebraRouteKernel = 1
    final val ZebraRouteConnect = 2
    final val ZebraRouteStatic = 3
    final val ZebraRouteRip = 4
    final val ZebraRouteRipng = 5
    final val ZebraRouteOspf = 6
    final val ZebraRouteOspf6 = 7
    final val ZebraRouteIsis = 8
    final val ZebraRouteBgp = 9
    final val ZebraRouteHsls = 10
    final val ZebraRouteMax = 11

    // Zebra route type string table.
    final val ZebraRouteTypeTable = Map(
        ZebraRouteSystem -> "System",
        ZebraRouteKernel -> "Kernel",
        ZebraRouteConnect -> "Connect",
        ZebraRouteStatic -> "Static",
        ZebraRouteRip -> "Rip",
        ZebraRouteRipng -> "Ripng",
        ZebraRouteOspf -> "Ospf",
        ZebraRouteOspf6 -> "Ospf6",
        ZebraRouteIsis -> "Isis",
        ZebraRouteBgp -> "Bgp",
        ZebraRouteHsls -> "Hsls")

    final def handleMessage(ctx: ZebraContext) = {
        // this is blocking
        val length = ctx.in.readUnsignedShort
        log.trace("length: {}/{}", length, ZebraMaxPayloadSize)

        val headerMarker = ctx.in.readUnsignedByte
        log.trace("headerMarker: {}/{}", headerMarker, ZebraHeaderMarker)

        val version = ctx.in.readByte
        log.trace("version: {}", version)

        val protocol = if (version == 3) {
            val vrfId = ctx.in.readUnsignedShort
            log.trace("Ignoring Zebra protocol v3 VRF ID field: {}", vrfId)
            ZebraProtocolV3
        } else {
            ZebraProtocolV2
        }

        val message = ctx.in.readUnsignedShort.toShort
        log.trace("message: {}", protocol.messageNames(message))

        protocol.handleMessage(message, ctx)
    }

}

object ZebraProtocolV2 extends ZebraProtocol {

    override val version: Int = 2
    override val headerSize: Int = 6
    override val messageNames: Map[Short, String] = Map(
        ZebraInterfaceAdd -> "ZebraInterfaceAdd",
        ZebraInterfaceDelete -> "ZebraInterfaceDelete",
        ZebraInterfaceAddressAdd -> "ZebraInterfaceAddressAdd",
        ZebraInterfaceAddressDelete -> "ZebraInterfaceAddressDelete",
        ZebraInterfaceUp -> "ZebraInterfaceUp",
        ZebraInterfaceDown -> "ZebraInterfaceDown",
        ZebraIpv4RouteAdd -> "ZebraIpv4RouteAdd",
        ZebraIpv4RouteDelete -> "ZebraIpv4RouteDelete",
        ZebraIpv6RouteAdd -> "ZebraIpv6RouteAdd",
        ZebraIpv6RouteDelete -> "ZebraIpv6RouteDelete",
        ZebraRedistributeAdd -> "ZebraRedistributeAdd",
        ZebraRedistributeDelete -> "ZebraRedistributeDelete",
        ZebraRedistributeDefaultAdd -> "ZebraRedistributeDefaultAdd",
        ZebraRedistributeDefaultDelete -> "ZebraRedistributeDefaultDelete",
        ZebraIpv4NextHopLookup -> "ZebraIpv4NextHopLookup",
        ZebraIpv6NextHopLookup -> "ZebraIpv6NextHopLookup",
        ZebraIpv4ImportLookup -> "ZebraIpv4ImportLookup",
        ZebraIpv6ImportLookup -> "ZebraIpv6ImportLookup",
        ZebraInterfaceRename -> "ZebraInterfaceRename",
        ZebraRouterIdAdd -> "ZebraRouterIdAdd",
        ZebraRouterIdDelete -> "ZebraRouterIdDelete",
        ZebraRouterIdUpdate -> "ZebraRouterIdUpdate",
        ZebraHello -> "ZebraHello"
    )
    override val messageSizes: Map[Short, Byte] = Map(
        ZebraInterfaceAdd -> 59,
        ZebraRouterIdUpdate -> 6,
        ZebraInterfaceAddressAdd -> 15,
        ZebraIpv4NextHopLookup -> 14
    )

    override def sendHeader(out: DataOutputStream, message: Short) {
        val length = messageSizes(message)

        log.trace("length: {}/{}", headerSize + length, ZebraMaxPayloadSize)
        log.trace("headerMarker: {}", ZebraHeaderMarker)
        log.trace("version: {}", version)
        log.trace("message: {}", messageNames(message))

        out.writeShort(headerSize + length)
        out.writeByte(ZebraHeaderMarker)
        out.writeByte(version)
        out.writeShort(message)
    }

    override def handleMessage(message: Short, ctx: ZebraContext): Unit =
        message match {
            case ZebraInterfaceAdd => interfaceAdd(ctx, this)
            case ZebraIpv4RouteAdd => ipv4RouteAdd(ctx)
            case ZebraIpv4RouteDelete => ipv4RouteDelete(ctx)
            case ZebraRouterIdAdd => routerIdUpdate(ctx, this)
            case ZebraIpv4NextHopLookup => nextHopLookup(ctx, this)
            case ZebraHello => hello(ctx)
            case _ => unsupported(message)
        }
}

object ZebraProtocolV3 extends ZebraProtocol {

    override val version: Int = 3
    override val headerSize: Int = 8
    override val messageNames: Map[Short, String] =
        ZebraProtocolV2.messageNames ++ Map(
            ZebraIpv4NexthopLookupMrib -> "ZebraIpv4NexthopLookupMrib",
            ZebraVrfUnregister -> "ZebraVrfUnregister",
            ZebraInterfaceLinkParams -> "ZebraInterfaceLinkParams",
            ZebraNextHopUnregister -> "ZebraNextHopUnregister",
            ZebraNextHopRegister -> "ZebraNextHopRegister",
            ZebraNextHopUpdate -> "ZebraNextHopUpdate"
        )
    override val messageSizes: Map[Short, Byte] = Map(
        ZebraInterfaceAdd -> 64,
        ZebraRouterIdUpdate -> 6,
        ZebraInterfaceAddressAdd -> 15,
        ZebraIpv4NextHopLookup -> 14,
        ZebraNextHopUpdate -> 17
    )

    override def sendHeader(out: DataOutputStream, message: Short) {
        val length = messageSizes(message)

        log.trace("length: {}/{}", headerSize + length, ZebraMaxPayloadSize)
        log.trace("headerMarker: {}", ZebraHeaderMarker)
        log.trace("version: {}", version)
        log.trace("sending header for message: {}", messageNames(message))

        out.writeShort(headerSize + length)
        out.writeByte(ZebraHeaderMarker)
        out.writeByte(version)
        out.writeShort(ZebraDefaultVrf)
        out.writeShort(message)
    }

    override def handleMessage(message: Short, ctx: ZebraContext): Unit =
        message match {
            case ZebraInterfaceAdd => interfaceAdd(ctx, this)
            case ZebraIpv4RouteAdd => ipv4RouteAdd(ctx)
            case ZebraIpv4RouteDelete => ipv4RouteDelete(ctx)
            case ZebraRouterIdAdd => routerIdUpdate(ctx, this)
            case ZebraIpv4NextHopLookup => nextHopLookup(ctx, this)
            case ZebraHello => hello(ctx)
            case ZebraNextHopRegister => nextHopRegister(ctx, this)
            case _ => unsupported(message)
        }
}
