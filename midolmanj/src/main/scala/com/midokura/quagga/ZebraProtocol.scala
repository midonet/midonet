/*
 * Copyright 2012 Midokura Pte. Ltd.
 */

package com.midokura.quagga

import java.io.{DataInputStream, DataOutputStream}

object ZebraProtocol {

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
    final val AF_INET  = 2
    final val AF_INET6 = 10
    final val Ipv4MaxBytelen = 4
    final val Ipv6MaxBytelen = 16

    // Zebra protocol headers.
    final val ZebraHeaderSize     = 6
    final val ZebraHeaderMarker   = 255
    final val ZebraHeaderVersion  = 1
    final val ZebraMaxPayloadSize = (1 << 16) - 1

    // Zebra message types.
    final val ZebraInterfaceAdd:Short               = 1
    final val ZebraInterfaceDelete:Short            = 2
    final val ZebraInterfaceAddressAdd:Short        = 3
    final val ZebraInterfaceAddressDelete:Short     = 4
    final val ZebraInterfaceUp:Short                = 5
    final val ZebraInterfaceDown:Short              = 6
    final val ZebraIpv4RouteAdd:Short               = 7
    final val ZebraIpv4RouteDelete:Short            = 8
    final val ZebraIpv6RouteAdd:Short               = 9
    final val ZebraIpv6RouteDelete:Short            = 10
    final val ZebraRedistributeAdd:Short            = 11
    final val ZebraRedistributeDelete:Short         = 12
    final val ZebraRedistributeDefaultAdd:Short     = 13
    final val ZebraRedistributeDefaultDelete:Short  = 14
    final val ZebraIpv4NextHopLookup:Short          = 15
    final val ZebraIpv6NextHopLookup:Short          = 16
    final val ZebraIpv4ImportLookup:Short           = 17
    final val ZebraIpv6ImportLookup:Short           = 18
    final val ZebraInterfaceRename:Short            = 19
    final val ZebraRouterIdAdd:Short                = 20
    final val ZebraRouterIdDelete:Short             = 21
    final val ZebraRouterIdUpdate:Short             = 22
    final val ZebraMessageMax:Short                 = 23

    // Interface related constants.
    final val InterfaceNameSize  = 20
    final val InterfaceHwAddrMax = 20
    final val MacAddrLength      = 6
    final val ZebraInterfaceActive        = 1 << 0
    final val ZebraInterfaceSub           = 1 << 1
    final val ZebraInterfaceLinkdetection = 1 << 2

    // Zebra message payload sizes.
    // InterfaceNameSize + MacAddrLength + Long + Int * 6 + Byte
    final val ZebraInterfaceAddSize =
        (InterfaceNameSize + MacAddrLength + 8 + 4 * 6 + 1).toByte
    // Ipv4MaxBytelen + Byte * 2
    final val ZebraRouterIdUpdateSize = (Ipv4MaxBytelen + 1 * 2).toByte
    // Ipv4MaxBytelen + Int * 2 + Byte * 3
    final val ZebraInterfaceAddressAddSize = (Ipv4MaxBytelen +
        4 * 2 + 1 * 3).toByte

    // c.f. /usr/include/net/if.h
    final val IFF_UP          = 1 << 0
    final val IFF_BROADCAST   = 1 << 1
    final val IFF_DEBUG       = 1 << 2
    final val IFF_LOOPBACK    = 1 << 3
    final val IFF_POINTOPOINT = 1 << 4
    final val IFF_NOTRAILERS  = 1 << 5
    final val IFF_RUNNING     = 1 << 6
    final val IFF_NOARP       = 1 << 7
    final val IFF_PROMISC     = 1 << 8
    final val IFF_ALLMULTI    = 1 << 9
    final val IFF_MASTER      = 1 << 10
    final val IFF_SLAVE       = 1 << 11
    final val IFF_MULTICAST   = 1 << 12
    final val IFF_PORTSEL     = 1 << 13
    final val IFF_AUTOMEDIA   = 1 << 14
    final val IFF_DYNAMIC     = 1 << 15

    // Flags for connected address.
    final val ZEBRA_IFA_SECONDARY = 1 << 0
    final val ZEBRA_IFA_PEER      = 1 << 1

    // Zebra IPv4 route message API.
    final val ZAPIMessageNextHop  = 1 << 0
    final val ZAPIMessageIfIndex  = 1 << 1
    final val ZAPIMessageDistance = 1 << 2
    final val ZAPIMessageMetric   = 1 << 3

    // Zebra nexthop flags.
    final val ZebraNexthopIfIndex     = 1
    final val ZebraNextHopIfName      = 2
    final val ZebraNextHopIpv4        = 3
    final val ZebraNextHopIpv4IfIndex = 4
    final val ZebraNextHopIpv4IfName  = 5
    final val ZebraNextHopIpv6        = 6
    final val ZebraNextHopIpv6IfIndex = 7
    final val ZebraNextHopIpv6IfName  = 8
    final val ZebraNextHopBlackhole   = 9

    // Zebra route types.
    final val ZebraRouteSystem  = 0
    final val ZebraRouteKernel  = 1
    final val ZebraRouteConnect = 2
    final val ZebraRouteStatic  = 3
    final val ZebraRouteRip     = 4
    final val ZebraRouteRipng   = 5
    final val ZebraRouteOspf    = 6
    final val ZebraRouteOspf6   = 7
    final val ZebraRouteIsis    = 8
    final val ZebraRouteBgp     = 9
    final val ZebraRouteHsls    = 10
    final val ZebraRouteMax     = 11

    final def sendHeader(out: DataOutputStream, message: Short, length: Int) {
        assert(ZebraHeaderSize + length <= ZebraMaxPayloadSize)

        out.writeShort(ZebraHeaderSize + length)
        out.writeByte(ZebraHeaderMarker)
        out.writeByte(ZebraHeaderVersion)
        out.writeShort(message)
    }

    final def recvHeader(in: DataInputStream): (Short, Int) = {
        // this is blocking
        val length = in.readUnsignedShort
        assert(length <= ZebraMaxPayloadSize)
        val headerMarker = in.readByte
        val version = in.readByte
        val message = in.readUnsignedShort.toShort

        (message, length - ZebraHeaderSize)
    }
}


