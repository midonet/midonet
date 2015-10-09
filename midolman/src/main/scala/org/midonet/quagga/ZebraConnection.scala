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

import akka.actor.{ActorRef, Actor}
import akka.event.LoggingReceive
import java.io._
import java.net.InetAddress
import java.nio.channels.{Channels, ByteChannel}

import org.midonet.midolman.state.NoStatePathException
import org.midonet.midolman.logging.ActorLogWithoutPath
import org.midonet.packets.{IPv4Addr, IPv4Subnet}
import org.midonet.quagga.ZebraProtocol.RIBType

case object ProcessMessage

object ZebraConnection {
    // The mtu size 1300 to avoid ovs dropping packets.
    private final val MidolmanMTU = 1300
}

class ZebraConnection(val dispatcher: ActorRef,
                      val handler: ZebraProtocolHandler,
                      val ifAddr: IPv4Addr,
                      val ifName: String,
                      val clientId: Int,
                      val channel: ByteChannel)
    extends Actor with ActorLogWithoutPath {

    import ZebraConnection._
    import ZebraProtocol._

    override def logSource = s"org.midonet.routing.bgp.zebra-server-$ifName"

    implicit def inputStreamWrapper(in: InputStream) = new DataInputStream(in)
    implicit def outputStreamWrapper(out: OutputStream) = new DataOutputStream(out)

    val in: DataInputStream = Channels.newInputStream(channel)
    val out: DataOutputStream = Channels.newOutputStream(channel)

    private def sendInterfaceName(out: DataOutputStream, ifName: String) {
        val ifNameBuf = new StringBuffer(ifName)
        ifNameBuf.setLength(InterfaceNameSize)
        out.writeBytes(ifNameBuf.toString)
        log.trace("ifName: {}", ifName)
    }

    private def sendInterfaceIndex(out: DataOutputStream, ifIndex: Int) {
        out.writeInt(ifIndex)
        log.trace(s"ifIndex: $ifIndex")
    }

    private def sendInterfaceStatus(out: DataOutputStream, status: Int) {
        out.writeByte(status.toByte)
        log.trace(s"status: $status")
    }

    private def sendInterfaceFlags(out: DataOutputStream, flags: Int) {
        out.writeLong(flags)
        log.trace("flags: 0x%x".format(flags))
    }

    private def sendInterfaceMetric(out: DataOutputStream, metric: Int) {
        out.writeInt(metric)
        log.trace(s"metric: $metric")
    }

    private def sendInterfaceMtu(out: DataOutputStream, mtu: Int) {
        out.writeInt(mtu)
        log.trace(s"MTU: $mtu")
    }

    private def sendInterfaceMtu6(out: DataOutputStream, mtu6: Int) {
        out.writeInt(mtu6)
        log.trace(s"MTU6: $mtu6")
    }

    private def sendInterfaceBandwidth(out: DataOutputStream, bandwidth: Int) {
        out.writeInt(bandwidth)
        log.trace(s"bandwidth: $bandwidth")
    }

    private def sendInterfaceHwLen(out: DataOutputStream, hwLen: Int) {
        out.writeInt(hwLen)
        log.trace(s"hwLen: $hwLen")
    }

    private def sendInterfaceHwAddr(out: DataOutputStream) {
        val mac = new Array[Byte](MacAddrLength)
        out.write(mac, 0, MacAddrLength)
        log.trace(s"hwAddr; ${mac.toString}")
    }

    private def sendInterfaceAddrIndex(out: DataOutputStream, ifIndex: Int) {
        out.writeInt(ifIndex)
        log.trace(s"ifIndex: $ifIndex")
    }

    private def sendInterfaceAddrFlags(out: DataOutputStream, flags: Byte) {
        out.writeByte(flags)
        log.trace(s"flags: $flags")
    }

    private def sendInterfaceAddrFamily(out: DataOutputStream, family: Int) {
        out.writeByte(family.toByte)
        log.trace(s"family: $family")
    }

    private def sendInterfaceAddr(out: DataOutputStream, ipv4addr: Int) {
        out.writeInt(ipv4addr)
        log.trace(s"addr: 0x$ipv4addr%x")
    }

    private def sendInterfaceAddrLen(out: DataOutputStream, addrLen: Byte) {
        out.writeByte(addrLen)
        log.trace(s"addrLen: $addrLen")
    }

    private def sendInterfaceAddrDstAddr(out: DataOutputStream, ipv4addr: Int) {
        out.writeInt(ipv4addr)
        log.trace(s"dstAddr: 0x$ipv4addr%x")
    }

    private def interfaceAdd() {
        log.debug(s"adding interface, clientId: $clientId")

        val ifAddr4: IPv4Addr = ifAddr match {
            case ia: IPv4Addr => ia
            case _ => throw new IllegalArgumentException("Zebra interface " +
                        "address only supports IPv4")
        }

        sendHeader(out, ZebraInterfaceAdd, ZebraInterfaceAddSize)
        sendInterfaceName(out, ifName)
        sendInterfaceIndex(out, 0)
        sendInterfaceStatus(out, ZebraInterfaceActive)
        sendInterfaceFlags(out, IFF_UP | IFF_BROADCAST | IFF_PROMISC | IFF_MULTICAST)
        sendInterfaceMetric(out, 1)
        sendInterfaceMtu(out, MidolmanMTU)
        sendInterfaceMtu6(out, MidolmanMTU)
        sendInterfaceBandwidth(out, 0)
        sendInterfaceHwLen(out, MacAddrLength)
        sendInterfaceHwAddr(out)


        sendHeader(out, ZebraInterfaceAddressAdd,
            ZebraInterfaceAddressAddSize)
        sendInterfaceAddrIndex(out, 0)
        sendInterfaceAddrFlags(out, 0)
        sendInterfaceAddrFamily(out, AF_INET)
        sendInterfaceAddr(out, ifAddr4.toInt)
        sendInterfaceAddrLen(out, 4)
        sendInterfaceAddrDstAddr(out, 0)
    }

    def routerIdUpdate() {
        // TODO(pino): shouldn't we be using the router's IP address,
        // TODO(pino): not that of the local host?
        val ia = InetAddress.getLocalHost
        sendHeader(out, ZebraRouterIdUpdate, ZebraRouterIdUpdateSize)
        out.writeByte(AF_INET)
        out.write(ia.getAddress, 0, Ipv4MaxBytelen)
        // prefix length
        out.writeByte(32)
        log.debug("ZebraRouterIdUpdate: %s".format(ia.getHostAddress))
    }

    private def readPrefix(): IPv4Subnet = {
        val prefixLen = in.readByte
        log.trace(s"prefixLen: $prefixLen")
        val prefix = new Array[Byte](Ipv4MaxBytelen)
        // Protocol daemons only send network part.
        in.read(prefix, 0, ((prefixLen + 7) / 8))
        log.trace("prefix: {}", prefix.map(_.toInt))

        new IPv4Subnet(IPv4Addr.fromBytes(prefix), prefixLen)
    }

    def ipv4RouteAdd() {
        log.trace("adding ipv4 route")

        val ribType = in.readByte
        val flags = in.readByte
        log.trace(f"ribType:$ribType flags: 0x$flags%x")

        val message = in.readByte
        log.trace(s"message: ${ZebraMessageTable(message)}($message)")

        val safi = in.readShort
        val prefix = readPrefix()

        if (!ZebraRouteTypeTable.contains(ribType)) {
            log.error(s"Wrong RIB type: $ribType")
            return
        }

        log.debug("ZebraIpv4RouteAdd: ribType %s flags %d prefix %s".format(
            ZebraRouteTypeTable(ribType), flags, prefix))

        val (nextHops, distance) = readRoutes(message)

        val paths = nextHops map {
            case hop => ZebraPath(RIBType.fromInteger(ribType), hop, distance)
        }

        handler.addRoutes(prefix, paths.toSet)

        if ((message & ZAPIMessageMetric) != 0) {
            // droping for now.
            val metric = in.readInt
        }
    }

    def ipv4RouteDelete() {
        log.trace("deleting ipv4 route")

        val ribType = in.readByte
        val flags = in.readByte
        val message = in.readByte
        val safi = in.readShort
        val prefix = readPrefix()

        assert(ZebraRouteTypeTable.contains(ribType))

        log.debug(
            s"ZebraIpv4RouteDelete: ribType ${ZebraRouteTypeTable(ribType)} flags $flags prefix $prefix")
        val (nextHops, _) = readRoutes(message)

        for (hop <- nextHops) {
            handler.removeRoute(RIBType.fromInteger(ribType), prefix, hop)
        }

        if ((message & ZAPIMessageMetric) != 0) {
            val metric = in.readInt // droping for now.
        }
    }

    def hello() {
        val proto = in.readByte
        log.debug(s"hello, client: $clientId proto: $proto")
        assert(proto == ZebraRouteBgp)
    }

    def handleMessage(message: Short) {
        def unsupported() {
            val msg = ZebraMessageTable.get(message).getOrElse("unrecognized-message")
            log.error(s"$msg isn't implemented yet")
            throw new RuntimeException("not implemented")
        }

        message match {
            case ZebraInterfaceAdd => interfaceAdd()
            case ZebraIpv4RouteAdd => ipv4RouteAdd()
            case ZebraIpv4RouteDelete => ipv4RouteDelete()
            case ZebraRouterIdAdd => routerIdUpdate()
            case ZebraHello => hello()

            case _ => unsupported()
        }
    }

    private def readRoutes(message: Byte): (Array[IPv4Addr], Byte) = {
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

    override def postStop() {
        if (channel.isOpen)
            channel.close()
    }

    override def receive = LoggingReceive {
        case ProcessMessage =>
            try {
                val (message, _) = recvHeader(in)
                handleMessage(message)
                self ! ProcessMessage
            } catch {
                case e: NoStatePathException =>
                    log.warn("config isn't in the directory")
                    dispatcher ! ConnectionClosed(clientId)
                case e: EOFException =>
                    log.warn("connection closed by the peer")
                    dispatcher ! ConnectionClosed(clientId)
                case e: IOException =>
                    log.warn("IO error", e)
                    dispatcher ! ConnectionClosed(clientId)
                case e: Throwable =>
                    log.warn("unexpected error", e)
                    dispatcher ! ConnectionClosed(clientId)
            }

        case _ => {
            log.error("received unknown request")
        }
    }
}
