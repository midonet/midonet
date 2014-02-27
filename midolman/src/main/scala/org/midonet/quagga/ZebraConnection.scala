/*
 * Copyright 2012 Midokura Pte. Ltd.
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

    implicit def inputStreamWrapper(in: InputStream) = new DataInputStream(in)
    implicit def outputStreamWrapper(out: OutputStream) = new DataOutputStream(out)

    val in: DataInputStream = Channels.newInputStream(channel)
    val out: DataOutputStream = Channels.newOutputStream(channel)

    private def sendInterfaceName(out: DataOutputStream, ifName: String) {
        val ifNameBuf = new StringBuffer(ifName)
        ifNameBuf.setLength(InterfaceNameSize)
        out.writeBytes(ifNameBuf.toString)
        log.debug("ifName: {}", ifNameBuf)
    }

    private def sendInterfaceIndex(out: DataOutputStream, ifIndex: Int) {
        out.writeInt(ifIndex)
        log.debug("ifIndex: {}", ifIndex)
    }

    private def sendInterfaceStatus(out: DataOutputStream, status: Int) {
        out.writeByte(status.toByte)
        log.debug("status: {}", status)
    }

    private def sendInterfaceFlags(out: DataOutputStream, flags: Int) {
        out.writeLong(flags)
        log.debug("flags: 0x%x".format(flags))
    }

    private def sendInterfaceMetric(out: DataOutputStream, metric: Int) {
        out.writeInt(metric)
        log.debug("metric: {}", metric)
    }

    private def sendInterfaceMtu(out: DataOutputStream, mtu: Int) {
        out.writeInt(mtu)
        log.debug("MTU: {}", mtu)
    }

    private def sendInterfaceMtu6(out: DataOutputStream, mtu6: Int) {
        out.writeInt(mtu6)
        log.debug("MTU6: {}", mtu6)
    }

    private def sendInterfaceBandwidth(out: DataOutputStream, bandwidth: Int) {
        out.writeInt(bandwidth)
        log.debug("Bandwidth: {}", bandwidth)
    }

    private def sendInterfaceHwLen(out: DataOutputStream, hwLen: Int) {
        out.writeInt(hwLen)
        log.debug("hwLen: {}", hwLen)
    }

    private def sendInterfaceHwAddr(out: DataOutputStream) {
        val mac = new Array[Byte](MacAddrLength)
        out.write(mac, 0, MacAddrLength)
        log.debug("hwAddr; {}", mac.toString)
    }

    private def sendInterfaceAddrIndex(out: DataOutputStream, ifIndex: Int) {
        out.writeInt(ifIndex)
        log.debug("ifIndex: {}", ifIndex)
    }

    private def sendInterfaceAddrFlags(out: DataOutputStream, flags: Byte) {
        out.writeByte(flags)
        log.debug("flags: {}", flags)
    }

    private def sendInterfaceAddrFamily(out: DataOutputStream, family: Int) {
        out.writeByte(family.toByte)
        log.debug("family: {}", family)
    }

    private def sendInterfaceAddr(out: DataOutputStream, ipv4addr: Int) {
        out.writeInt(ipv4addr)
        log.debug("addr: %x".format(ipv4addr))
    }

    private def sendInterfaceAddrLen(out: DataOutputStream, addrLen: Byte) {
        out.writeByte(addrLen)
        log.debug("addrLen: {}", addrLen)
    }

    private def sendInterfaceAddrDstAddr(out: DataOutputStream, ipv4addr: Int) {
        out.writeInt(ipv4addr)
        log.debug("dstAddr: %x".format(ipv4addr))
    }

    private def interfaceAdd() {
        log.debug("begin, clientId: {}", clientId)

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

        log.debug("end")
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

    def ipv4RouteAdd() {
        log.debug("begin")
        val ribType = in.readByte
        log.debug("ribType: {}", ribType)

        val flags = in.readByte
        log.debug("flags: 0x%x".format(flags))

        val message = in.readByte
        log.debug("message: {}({})", ZebraMessageTable(message), message)

        val safi = in.readShort
        log.debug("safi: {}", safi)

        val prefixLen = in.readByte
        log.debug("prefixLen: {}", prefixLen)

        val prefix = new Array[Byte](Ipv4MaxBytelen)
        // Protocol daemons only send network part.
        in.read(prefix, 0, ((prefixLen + 7) / 8))
        log.debug("prefix: {}", prefix.map(_.toInt))

        if (!ZebraRouteTypeTable.contains(ribType)) {
            log.error("Wrong RIB type: {}", ribType)
            return
        }

        val advertised = "%s/%d".format(
            InetAddress.getByAddress(prefix).getHostAddress, prefixLen)
        log.info("ZebraIpv4RouteAdd: ribType %s flags %d prefix %s".format(
            ZebraRouteTypeTable(ribType), flags, advertised))

        if ((message & ZAPIMessageNextHop) != 0) {
            val nextHopNum = in.readByte
            for (i <- 0 until nextHopNum) {
                val nextHopType = in.readByte
                if (nextHopType == ZebraNextHopIpv4) {
                    val addr = in.readInt
                    log.info(
                        "ZebraIpv4RouteAdd: nextHopType %d addr %d".format(
                            nextHopType, addr))
                    handler.addRoute(RIBType.fromInteger(ribType),
                        new IPv4Subnet(IPv4Addr.fromBytes(prefix), prefixLen),
                        IPv4Addr.fromInt(addr))
                }
            }
        }

        if ((message & ZAPIMessageDistance) != 0) {
            val distance = in.readByte
            // droping for now.
        }

        if ((message & ZAPIMessageMetric) != 0) {
            // droping for now.
            val metric = in.readInt
        }
        log.debug("end")
    }

    def ipv4RouteDelete() {
        log.debug("begin")

        val ribType = in.readByte
        val flags = in.readByte
        val message = in.readByte
        val safi = in.readShort
        val prefixLen = in.readByte
        val prefix = new Array[Byte](Ipv4MaxBytelen)
        // Protocol daemons only send network part.
        in.read(prefix, 0, ((prefixLen + 7) / 8))

        assert(ZebraRouteTypeTable.contains(ribType))

        val advertised = "%s/%d".format(
            InetAddress.getByAddress(prefix).getHostAddress, prefixLen)
        log.info(
            "ZebraIpv4RouteDelete: ribType %s flags %d prefix %s".format(
                ZebraRouteTypeTable(ribType), flags, advertised))

        if ((message & ZAPIMessageNextHop) != 0) {
            val nextHopNum = in.readByte
            for (i <- 0 until nextHopNum) {
                val nextHopType = in.readByte
                if (nextHopType == ZebraNextHopIpv4) {
                    val addr = in.readInt
                    log.info(
                        "ZebraIpv4RouteDelte: nextHopType %d addr %d".format(
                            nextHopType, addr))

                    handler.removeRoute(RIBType.fromInteger(ribType),
                        new IPv4Subnet(IPv4Addr.fromBytes(prefix), prefixLen),
                        IPv4Addr.fromInt(addr))
                }
            }
        }

        if ((message & ZAPIMessageDistance) != 0) {
            val distance = in.readByte
            // droping for now.
        }

        if ((message & ZAPIMessageMetric) != 0) {
            // droping for now.
            val metric = in.readInt
        }

        log.debug("end")
    }

    def hello() {
        log.debug("begin, clientId: {}", clientId)

        val proto = in.readByte
        log.debug("proto: {}", proto)
        assert(proto == ZebraRouteBgp)

        log.debug("end")
    }

    def handleMessage(message: Short) {
        def unsupported() {
            val msgStr = ZebraMessageTable.get(message).getOrElse("unrecognized-message")
            log.error("%s isn't implemented yet".format(msgStr))
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

    override def postStop() {
        if (channel.isOpen)
            channel.close()
    }

    override def receive = LoggingReceive {
        case ProcessMessage =>
            try {
                log.debug("ProcessMessage")
                val (message, _) = recvHeader(in)
                handleMessage(message)
                self ! ProcessMessage
            } catch {
                case e: NoStatePathException =>
                    log.warning("config isn't in the directory")
                    dispatcher ! ConnectionClosed(clientId)
                case e: EOFException =>
                    log.warning("connection closed by the peer")
                    dispatcher ! ConnectionClosed(clientId)
                case e: IOException =>
                    log.warning("IO error", e)
                    dispatcher ! ConnectionClosed(clientId)
                case e: Throwable =>
                    log.warning("unexpected error", e)
                    dispatcher ! ConnectionClosed(clientId)
            }

        case _ => {
            log.error("received unknown request")
        }
    }
}
