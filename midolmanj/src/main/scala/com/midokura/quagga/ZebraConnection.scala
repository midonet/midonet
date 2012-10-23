/*
 * Copyright 2012 Midokura Pte. Ltd.
 */

package com.midokura.quagga

import org.slf4j.LoggerFactory
import actors.Actor
import com.midokura.packets.IntIPv4
import collection.mutable
import java.util.UUID
import java.io._
import java.net.{Socket, InetAddress}
import com.midokura.midolman.state.NoStatePathException

case class HandleConnection(socket: Socket)

object ZebraConnection {
import ZebraProtocol._

// Midolman OpenvSwitch constants.
// TODO(yoshi): get some values from config file.
private final val BgpExtIdValue = "bgp"
private final val OspfExtIdValue = "ospf"
private final val RipfExtIdValue = "rip"

// The mtu size 1300 to avoid ovs dropping packets.
private final val MidolmanMTU = 1300
// The weight of advertised routes.
private final val AdvertisedWeight = 0
}

class ZebraConnection(val dispatcher: Actor,
                  val handler: ZebraProtocolHandler,
                  val ifAddr: IntIPv4,
                  val ifName: String,
                  val clientId: Int)
    extends Actor {

    import ZebraConnection._
    import ZebraProtocol._

    private final val log = LoggerFactory.getLogger(this.getClass)

    // Map to track zebra route and MidoNet Route.
    private val zebraToRoute = mutable.Map[String, UUID]()

    implicit def inputStreamWrapper(in: InputStream) =
        new DataInputStream(in)

    implicit def outputStreamWrapper(out: OutputStream) =
        new DataOutputStream(out)

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

    private def sendInterfaceAddrDstAddr(out:DataOutputStream, ipv4addr: Int) {
        out.writeInt(ipv4addr)
        log.debug("dstAddr: %x".format(ipv4addr))
    }

    private def interfaceAdd(out: DataOutputStream) {
        log.debug("begin, clientId: {}", clientId)

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
        sendInterfaceAddr(out, ifAddr.addressAsInt())
        sendInterfaceAddrLen(out, 4)
        sendInterfaceAddrDstAddr(out, 0)

        log.debug("end")
    }

    def routerIdUpdate(out: DataOutputStream) {
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

    def ipv4RouteAdd(in: DataInputStream) {
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

        assert(ZebraRouteTypeTable.contains(ribType))

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
                        new IntIPv4(prefix)
                            .setMaskLength(prefixLen),
                        new IntIPv4(addr))

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

    def ipv4RouteDelete(in: DataInputStream, out: DataOutputStream) {
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
                        new IntIPv4(prefix)
                            .setMaskLength(prefixLen),
                        new IntIPv4(addr))

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

    def hello(in: DataInputStream, out: DataOutputStream) {
        log.debug("begin, clientId: {}", clientId)

        val proto = in.readByte
        log.debug("proto: {}", proto)
        assert(proto == ZebraRouteBgp)

        log.debug("end")
    }

    def handleRequest(in: DataInputStream, out: DataOutputStream) {
        log.debug("begin - clientId: {}", clientId)

        while (true) {
            val (message, _) = recvHeader(in)

            message match {
                case ZebraInterfaceAdd =>
                { interfaceAdd(out) }
                case ZebraInterfaceDelete => {
                    log.error("%s isn't implemented yet".format(
                        ZebraMessageTable(message)))
                    throw new RuntimeException("not implemented")
                }
                case ZebraInterfaceAddressAdd => {
                    log.error("%s isn't implemented yet".format(
                        ZebraMessageTable(message)))
                    throw new RuntimeException("not implemented")
                }
                case ZebraInterfaceAddressDelete => {
                    log.error("%s isn't implemented yet".format(
                        ZebraMessageTable(message)))
                    throw new RuntimeException("not implemented")
                }
                case ZebraInterfaceUp => {
                    log.error("%s isn't implemented yet".format(
                        ZebraMessageTable(message)))
                    throw new RuntimeException("not implemented")
                }
                case ZebraInterfaceDown => {
                    log.error("%s isn't implemented yet".format(
                        ZebraMessageTable(message)))
                    throw new RuntimeException("not implemented")
                }
                case ZebraIpv4RouteAdd =>
                { ipv4RouteAdd(in) }
                case ZebraIpv4RouteDelete =>
                { ipv4RouteDelete(in, out) }
                case ZebraIpv6RouteAdd => {
                    log.error("%s isn't implemented yet".format(
                        ZebraMessageTable(message)))
                    throw new RuntimeException("not implemented")
                }
                case ZebraIpv6RouteDelete => {
                    log.error("%s isn't implemented yet".format(
                        ZebraMessageTable(message)))
                    throw new RuntimeException("not implemented")
                }
                case ZebraRedistributeAdd => {
                    log.error("%s isn't implemented yet".format(
                        ZebraMessageTable(message)))
                    throw new RuntimeException("not implemented")
                }
                case ZebraRedistributeDelete => {
                    log.error("%s isn't implemented yet".format(
                        ZebraMessageTable(message)))
                    throw new RuntimeException("not implemented")
                }
                case ZebraRedistributeDefaultAdd => {
                    log.error("%s isn't implemented yet".format(
                        ZebraMessageTable(message)))
                    throw new RuntimeException("not implemented")
                }
                case ZebraRedistributeDefaultDelete => {
                    log.error("%s isn't implemented yet".format(
                        ZebraMessageTable(message)))
                    throw new RuntimeException("not implemented")
                }
                case ZebraIpv4NextHopLookup => {
                    log.error("%s isn't implemented yet".format(
                        ZebraMessageTable(message)))
                    throw new RuntimeException("not implemented")
                }
                case ZebraIpv6NextHopLookup => {
                    log.error("%s isn't implemented yet".format(
                        ZebraMessageTable(message)))
                    throw new RuntimeException("not implemented")
                }
                case ZebraIpv4ImportLookup => {
                    log.error("%s isn't implemented yet".format(
                        ZebraMessageTable(message)))
                    throw new RuntimeException("not implemented")
                }
                case ZebraIpv6ImportLookup => {
                    log.error("%s isn't implemented yet".format(
                        ZebraMessageTable(message)))
                    throw new RuntimeException("not implemented")
                }
                case ZebraInterfaceRename => {
                    log.error("%s isn't implemented yet".format(
                        ZebraMessageTable(message)))
                    throw new RuntimeException("not implemented")
                }
                case ZebraRouterIdAdd =>
                { routerIdUpdate(out) }
                case ZebraRouterIdDelete => {
                    log.error("%s isn't implemented yet".format(
                        ZebraMessageTable(message)))
                    throw new RuntimeException("not implemented")
                }
                case ZebraRouterIdUpdate => {
                    log.error("%s isn't implemented yet".format(
                        ZebraMessageTable(message)))
                    throw new RuntimeException("not implemented")
                }
                case ZebraHello =>
                { hello(in, out) }
                case _ => {
                    log.error("received unknown message %s".format(message))
                }
            }
        }
    }

    def act() {
        loop {
            react {
                case HandleConnection(socket: Socket) => {
                    log.debug("clientId: {}", clientId)
                    try {
                        handleRequest(socket.getInputStream,
                                      socket.getOutputStream)
                    } catch {
                        case e: NoStatePathException =>
                        { log.warn("config isn't in the directory") }
                        case e: EOFException =>
                        { log.warn("connection closed by the peer") }
                        case e: IOException =>
                        { log.warn("IO error", e) }
                    } finally {
                        socket.close()
                        dispatcher ! Response(clientId)
                    }
                }
                case _ =>
                { log.error("received unknown request") }
            }
        }
    }
}

