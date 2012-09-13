/*
 * Copyright 2012 Midokura Pte. Ltd.
 */

package com.midokura.quagga

import org.slf4j.LoggerFactory
import actors.Actor
import com.midokura.midolman.routingprotocols.ZebraProtocolHandler
import com.midokura.packets.IntIPv4
import collection.mutable
import java.util.UUID
import java.io._
import java.net.InetAddress
import com.midokura.midolman.state.NoStatePathException

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

// Zebra message string table.
private final val ZebraMessageTable = Map(
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
    ZebraRouterIdUpdate -> "ZebraRouterIdUpdate")

// Zebra route type string table.
private final val ZebraRouteTypeTable = Map(
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

private final val log = LoggerFactory.getLogger(this.getClass)
}

class ZebraConnection(val dispatcher: Actor,
                  val handler: ZebraProtocolHandler,
                  val ifAddr: IntIPv4,
                  val ifName: String)
    extends Actor {

    import ZebraConnection._
    import ZebraProtocol._

    private final val log = LoggerFactory.getLogger(this.getClass)

    // Map to get port uuid from route type.
    private val ribTypeToPortUUID = mutable.Map[Int, String]()
    // Map to track zebra route and MidoNet Route.
    private val zebraToRoute = mutable.Map[String, UUID]()

    implicit def inputStreamWrapper(in: InputStream) =
        new DataInputStream(in)

    implicit def outputStreamWrapper(out: OutputStream) =
        new DataOutputStream(out)

    private def interfaceAdd(out: DataOutputStream) {
        //val protocols = Array(BgpExtIdValue, OspfExtIdValue, RipfExtIdValue)
        var ifIndex = 0

        /* TODO(pino): ask Yoshi why this was done per-protocol.
    for (protocol <- protocols) {
    val servicePort = ovsdb.synchronized {
        ovsdb.getPortNamesByExternalId(OvsPortServiceExtIdKey,
                                       protocol)
    }
    // Only one service port for each protocol at maximum.
    if (servicePort.size == 1) {
        val portName = servicePort.head
        // Get router port uuid.
        val portUUID = ovsdb.synchronized {         // remote virtual port
            ovsdb.getPortExternalId(portName, OvsPortIdExtIdKey)
        }
        assert(portUUID.nonEmpty)

        val portConfig = portMgr.synchronized {
            portMgr.get(UUID.fromString(portUUID),
                    classOf[MaterializedRouterPortConfig])
        }

        // Create a map to get port uuid from route type. Note that
        // this implementation will limit that each protocol can
        // only be handled by one router port.
        // TODO(yoshi): remove this limitation.
        protocol match {
            case RipfExtIdValue =>
                { ribTypeToPortUUID(ZebraRouteRip) = portUUID }
            case OspfExtIdValue =>
                { ribTypeToPortUUID(ZebraRouteOspf) = portUUID }
            case BgpExtIdValue =>
                { ribTypeToPortUUID(ZebraRouteBgp) = portUUID }
        }
        */

        sendHeader(out, ZebraInterfaceAdd, ZebraInterfaceAddSize)

        val ifNameBuf = new StringBuffer(ifName)
        // TODO(pino): why does the length need to be set exactly?
        ifNameBuf.setLength(InterfaceNameSize)
        out.writeBytes(ifNameBuf.toString)

        // Send bogus index now. c.f. ip link show <port>
        out.writeInt(ifIndex)
        out.writeByte(ZebraInterfaceActive)

        // Send flags that show up in internal ports.
        val flags = IFF_UP | IFF_BROADCAST | IFF_PROMISC | IFF_MULTICAST
        out.writeLong(flags)

        // metric
        out.writeInt(1)
        // mtu
        out.writeInt(MidolmanMTU)
        // mtu6
        out.writeInt(MidolmanMTU)
        // bandwidth
        out.writeInt(0)

        // Send empty mac address.
        // TODO(yoshi): replace this using
        // NetworkInterface.getByName(). But it'll blow up if OVS
        // internal port is up.
        out.writeInt(MacAddrLength)
        val mac = new Array[Byte](MacAddrLength)
        out.write(mac, 0, MacAddrLength)

        log.info("ZebraInterfaceAdd: index %d name %s".format(ifIndex,
            ifName))

        sendHeader(out, ZebraInterfaceAddressAdd,
            ZebraInterfaceAddressAddSize)
        out.writeInt(ifIndex)
        // ifc->flags
        out.write(0)
        out.write(AF_INET)

        // TODO(yoshi): Ipv6
        out.writeInt(ifAddr.addressAsInt())
        out.write(ifAddr.prefixLen())
        // TODO(yoshi): fix dummy destination address.
        out.writeInt(0)

        log.info("ZebraInterfaceAddressAdd: index %d name %s addr %d"
            .format(ifIndex, ifNameBuf, ifAddr))

        ifIndex += 1
        //} else if (servicePort.nonEmpty) {
        //    log.warn("Only one service port for each protocol")
        //}
        //}
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
        log.info("ZebraRouterIdUpdate: %s".format(ia.getHostAddress))
    }

    def ipv4RouteAdd(in: DataInputStream, out: DataOutputStream) {
        val ribType = in.readByte
        val flags = in.readByte
        val message = in.readByte
        val prefixLen:Byte = in.readByte
        val prefix = new Array[Byte](Ipv4MaxBytelen)
        // Protocol daemons only send network part.
        in.read(prefix, 0, ((prefixLen + 7) / 8))

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
    }

    def ipv4RouteDelete(in: DataInputStream, out: DataOutputStream) {
        val ribType = in.readByte
        val flags = in.readByte
        val message = in.readByte
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
    }

    def handleRequest(in: DataInputStream, out: DataOutputStream, id: Int) {
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
                { ipv4RouteAdd(in, out) }
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
                case _ => {
                    log.error("received unknown message %s".format(message))
                }
            }
        }
    }

    def act() {
        loop {
            react {
                case Request(conn, requestId) => {
                    log.info("received request %d".format(requestId))
                    try {
                        handleRequest(conn.getInputStream,
                            conn.getOutputStream, requestId)
                    } catch {
                        case e: NoStatePathException =>
                        { log.warn("config isn't in the directory") }
                        case e: EOFException =>
                        { log.warn("connection closed by the peer") }
                        case e: IOException =>
                        { log.warn("IO error", e) }
                    } finally {
                        conn.close()
                        dispatcher ! Response(requestId)
                    }
                }
                case _ =>
                { log.error("received unknown request") }
            }
        }
    }
}

