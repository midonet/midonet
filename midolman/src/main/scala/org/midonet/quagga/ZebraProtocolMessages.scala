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

import java.io.{DataInputStream, DataOutputStream}
import java.net.InetAddress

import org.midonet.netlink.rtnetlink.Link.Type.ARPHRD_ETHER
import org.midonet.packets.{IPv4Addr, IPv4Subnet, MAC}
import org.midonet.quagga.ZebraConnection.MidolmanMTU

object ZebraProtocolMessages {

    import ZebraProtocol._

    def ipv4RouteAdd(ctx: ZebraContext): Unit = {
        log.trace("adding ipv4 route")

        val ribType = ctx.in.readByte
        val flags = ctx.in.readByte
        val message = ctx.in.readByte
        val safi = ctx.in.readShort
        val prefix = readPrefix(ctx.in)

        if (!ZebraRouteTypeTable.contains(ribType)) {
            log.error(s"Wrong RIB type: $ribType")
            return
        }

        log.debug(s"ZebraIpv4RouteAdd: ribType " +
            s"${ZebraRouteTypeTable(ribType)} flags $flags prefix $prefix")
        val (nextHops, distance) = readRoutes(message, ctx.in)

        val paths = nextHops map { hop =>
            ZebraPath(RIBType.fromInteger(ribType), hop, distance)
        }

        ctx.handler.addRoutes(prefix, paths.toSet)

        if ((message & ZAPIMessageMetric) != 0) {
            val metric = ctx.in.readInt // dropping for now.
        }
    }

    def ipv4RouteDelete(ctx: ZebraContext): Unit = {
        log.trace("deleting ipv4 route")

        val ribType = ctx.in.readByte
        val flags = ctx.in.readByte
        val message = ctx.in.readByte
        val safi = ctx.in.readShort
        val prefix = readPrefix(ctx.in)

        if (!ZebraRouteTypeTable.contains(ribType)) {
            log.error(s"Wrong RIB type: $ribType")
            return
        }

        log.debug(s"ZebraIpv4RouteDelete: ribType " +
            s"${ZebraRouteTypeTable(ribType)} flags $flags prefix $prefix")
        val (nextHops, _) = readRoutes(message, ctx.in)

        for (hop <- nextHops) {
            ctx.handler.removeRoute(RIBType.fromInteger(ribType), prefix, hop)
        }

        if ((message & ZAPIMessageMetric) != 0) {
            val metric = ctx.in.readInt // dropping for now.
        }
    }

    def routerIdUpdate(ctx: ZebraContext, protocol: ZebraProtocol): Unit = {
        val ia = InetAddress.getLocalHost
        protocol.sendHeader(ctx.out, ZebraRouterIdUpdate)
        sendInterfaceAddressFamily(ctx.out, AF_INET)
        sendIpAddress(ctx.out, IPv4Addr.apply(ia.getAddress).addr)
        sendPrefixLength(ctx.out, 32)
        log.debug(s"ZebraRouterIdUpdate: ${ia.getHostAddress}")
    }

    def nextHopLookup(ctx: ZebraContext, protocol: ZebraProtocol): Unit = {
        val addr = ctx.in.readInt
        log.info(s"ZebraIpv4NextHopLookup for addr ${IPv4Addr.fromInt(addr)}")
        protocol.sendHeader(ctx.out, ZebraIpv4NextHopLookup)
        sendIpAddress(ctx.out, addr)
        sendInterfaceMetric(ctx.out, 1)
        sendNumberNextHops(ctx.out, 1)
        sendNextHopType(ctx.out, ZebraNextHopIpv4)
        sendIpAddress(ctx.out, addr) // gw addr
    }

    def hello(ctx: ZebraContext): Unit = {
        val proto = ctx.in.readByte
        log.debug(s"hello, client: ${ctx.clientId} proto: $proto")
        assert(proto == ZebraRouteBgp)
    }

    def interfaceAdd(ctx: ZebraContext, protocol: ZebraProtocol): Unit = {
        log.debug(s"adding interface, clientId: ${ctx.clientId}")

        protocol.sendHeader(ctx.out, ZebraInterfaceAdd)
        sendInterfaceName(ctx.out, ctx.ifName)
        sendInterfaceIndex(ctx.out, 0)
        sendInterfaceStatus(ctx.out, ZebraInterfaceActive)
        sendInterfaceFlags(ctx.out, IFF_UP | IFF_BROADCAST | IFF_PROMISC | IFF_MULTICAST)
        sendInterfaceMetric(ctx.out, 1)
        sendInterfaceMtu(ctx.out, MidolmanMTU)
        sendInterfaceMtu6(ctx.out, MidolmanMTU)
        sendInterfaceBandwidth(ctx.out, 0)
        if (protocol.version == 3) sendLinkLayerType(ctx.out)
        sendInterfaceHwLen(ctx.out, MacAddrLength)
        sendInterfaceHwAddr(ctx.out)
        if (protocol.version == 3) sendLinkParamsStatus(ctx.out)

        interfaceAddressAdd(ctx, protocol)
    }

    def interfaceAddressAdd(ctx: ZebraContext, protocol: ZebraProtocol): Unit = {
        val ifAddr4: IPv4Addr = ctx.ifAddr match {
            case ia: IPv4Addr => ia
            case _ => throw new IllegalArgumentException("Zebra interface " +
                "address only supports IPv4")
        }

        protocol.sendHeader(ctx.out, ZebraInterfaceAddressAdd)
        sendInterfaceIndex(ctx.out, 0)
        sendInterfaceAddressFlags(ctx.out, 0)
        sendInterfaceAddressFamily(ctx.out, AF_INET)
        sendIpAddress(ctx.out, ifAddr4.toInt)
        sendAddressLength(ctx.out, 4)
        sendInterfaceAddrDstAddr(ctx.out, 0)
    }

    /*
     * Upon receiving a nexthop_register message, the zebra node should answer
     * with a nexthop_update if the next hop is reachable or not  so that
     * route_add notifications will be sent to it. Otherwise these messages will
     * not be received. (Only valid in v3)
     *
     * TODO: To be super correct, we should make sure that the next hop in the
     * register message is actually routable (looking at the host routing table)
     * and send the update accordingly. Also, if we receive more than one next
     * hop, we should keep a list of the next hops and send back the update with
     * the status of all of them. For now, we just assume that all next hops
     * will be routable.
     */
    def nextHopRegister(ctx: ZebraContext, protocol: ZebraProtocol): Unit = {
        log.trace(s"received nextHopRegister message ...")
        val bgpNexthopConnected = ctx.in.readUnsignedByte == 1
        log.trace(s"bgpNexthopConnected: $bgpNexthopConnected")
        val prefixFamily = ctx.in.readUnsignedShort
        log.trace(s"prefixFamily: $prefixFamily")
        val prefixLength = ctx.in.readUnsignedByte
        log.trace(s"prefixLength: $prefixLength")
        val address = ctx.in.readInt
        log.trace(s"address: ${IPv4Addr.fromInt(address).toString}")

        // Send back an update
        protocol.sendHeader(ctx.out, ZebraNextHopUpdate)
        sendPrefixFamily(ctx.out, prefixFamily)
        sendPrefixLength(ctx.out, prefixLength)
        sendIpAddress(ctx.out, address)
        sendInterfaceMetric(ctx.out, 1)
        sendNumberNextHops(ctx.out, 1)
        sendNextHopType(ctx.out, ZebraNextHopIpv4)
        sendIpAddress(ctx.out, ctx.ifAddr.addr)
    }

    private def readPrefix(in: DataInputStream): IPv4Subnet = {
        val prefixLen = in.readByte
        val prefix = new Array[Byte](Ipv4MaxBytelen)
        // Protocol daemons only send network part.
        in.read(prefix, 0, (prefixLen + 7) / 8)

        new IPv4Subnet(IPv4Addr.fromBytes(prefix), prefixLen)
    }

    private def readRoutes(message: Byte,
                           in: DataInputStream): (Array[IPv4Addr], Byte) = {
        val nextHops =
            if ((message & ZAPIMessageNextHop) != 0) {
                val nextHopNum = in.readByte
                val hops = new Array[IPv4Addr](nextHopNum)
                for (i <- 0 until nextHopNum) {
                    val nextHopType = in.readByte
                    if (nextHopType == ZebraNextHopIpv4) {
                        val addr = in.readInt
                        log.info(s"received route: nextHopType $nextHopType addr $addr")
                        hops(i) = IPv4Addr.fromInt(addr)
                    }
                }
                hops
            } else {
                Array[IPv4Addr]()
            }

        val distance =
            if ((message & ZAPIMessageDistance) != 0) {
                in.readByte
            }  else {
                1.toByte
            }

        (nextHops, distance)
    }

    private def sendInterfaceName(out: DataOutputStream,
                                  ifName: String): Unit = {
        val ifNameBuf = new StringBuffer(ifName)
        ifNameBuf.setLength(InterfaceNameSize)
        out.writeBytes(ifNameBuf.toString)
        log.trace(s"ifName: $ifName")
    }

    private def sendInterfaceIndex(out: DataOutputStream,
                                   ifIndex: Int): Unit = {
        out.writeInt(ifIndex)
        log.trace(s"ifIndex: $ifIndex")
    }

    private def sendInterfaceStatus(out: DataOutputStream,
                                    status: Int): Unit = {
        out.writeByte(status.toByte)
        log.trace(s"status: $status")
    }

    private def sendInterfaceFlags(out: DataOutputStream, flags: Int): Unit = {
        out.writeLong(flags)
        log.trace(f"flags: 0x$flags%x")
    }

    private def sendInterfaceMetric(out: DataOutputStream,
                                    metric: Int): Unit = {
        out.writeInt(metric)
        log.trace(s"metric: $metric")
    }

    private def sendInterfaceMtu(out: DataOutputStream, mtu: Int): Unit = {
        out.writeInt(mtu)
        log.trace(s"MTU: $mtu")
    }

    private def sendInterfaceMtu6(out: DataOutputStream, mtu6: Int): Unit = {
        out.writeInt(mtu6)
        log.trace(s"MTU6: $mtu6")
    }

    private def sendInterfaceBandwidth(out: DataOutputStream,
                                       bandwidth: Int): Unit = {
        out.writeInt(bandwidth)
        log.trace(s"bandwidth: $bandwidth")
    }

    private def sendLinkLayerType(out: DataOutputStream,
                                  linkLayerType: Int = ARPHRD_ETHER): Unit = {
        out.writeInt(linkLayerType)
        log.trace(s"linkLayerType: $linkLayerType")
    }

    private def sendInterfaceHwLen(out: DataOutputStream, hwLen: Int): Unit = {
        out.writeInt(hwLen)
        log.trace(s"hwLen: $hwLen")
    }

    private def sendInterfaceHwAddr(out: DataOutputStream,
                                    mac: MAC = MAC.ALL_ZEROS): Unit = {
        out.write(mac.getAddress, 0, MacAddrLength)
        log.trace(s"hwAddr: $mac")
    }

    private def sendLinkParamsStatus(out: DataOutputStream,
                                     linkParamsStatus: Int = 0): Unit = {
        out.writeByte(linkParamsStatus)
        log.trace(s"linkParamsStatus: $linkParamsStatus")
    }

    private def sendPrefixFamily(out: DataOutputStream, family: Int): Unit = {
        out.writeShort(family.toByte)
        log.trace(s"prefixFamily: $family")
    }

    private def sendInterfaceAddressFamily(out: DataOutputStream,
                                           family: Int): Unit = {
        out.writeByte(family.toByte)
        log.trace(s"addressFamily: $family")
    }

    private def sendIpAddress(out: DataOutputStream, ipv4addr: Int): Unit = {
        out.writeInt(ipv4addr)
        log.trace(s"address: ${IPv4Addr.fromInt(ipv4addr)}")
    }

    private def sendAddressLength(out: DataOutputStream,
                                  addressLength: Byte): Unit = {
        out.writeByte(addressLength)
        log.trace(s"addressLength: $addressLength")
    }

    private def sendInterfaceAddrDstAddr(out: DataOutputStream,
                                         ipv4addr: Int): Unit = {
        out.writeInt(ipv4addr)
        log.trace(s"dstAddr: ${IPv4Addr.fromInt(ipv4addr)}")
    }

    private def sendPrefixLength(out: DataOutputStream,
                                 prefixLength: Int): Unit = {
        out.writeByte(prefixLength)
        log.trace(s"prefixLength: $prefixLength")
    }

    private def sendInterfaceAddressFlags(out: DataOutputStream,
                                          flags: Byte): Unit = {
        out.writeByte(flags)
        log.trace(f"flags: $flags")
    }

    private def sendNumberNextHops(out: DataOutputStream,
                                   nextHops: Int): Unit = {
        out.writeByte(nextHops)
        log.trace(s"numberNextHops: $nextHops")
    }

    private def sendNextHopType(out: DataOutputStream,
                                nextHopType: Int): Unit = {
        out.writeByte(nextHopType)
        log.trace(s"nextHopType: $nextHopType")
    }
}
