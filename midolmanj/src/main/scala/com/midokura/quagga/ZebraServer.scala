/**
 * ZebraServer.scala - Quagga Zebra server classes.
 *
 * A pure Scala implementation of the Quagga Zebra server to connect
 * MidoNet and protocol daemons like BGP, OSPF and RIP.
 *
 * This module can connected using a Unix domain or a TCP server socket,
 * where Quagga uses a Unix domain socket by default.
 *
 * Copyright (c) 2011 Midokura KK. All rights reserved.
 */

package com.midokura.quagga

import com.midokura.midolman.state.NoStatePathException

import scala.actors._
import scala.actors.Actor._
import scala.collection.mutable
import scala.collection.mutable.ListBuffer

import java.io.{DataInputStream, DataOutputStream, EOFException, InputStream,
                IOException, OutputStream}
import java.lang.StringBuffer
import java.net.{InetAddress, ServerSocket, Socket, SocketAddress}
import java.util.UUID

import org.slf4j.LoggerFactory
import com.midokura.packets.IntIPv4
import com.midokura.midolman.routingprotocols.ZebraProtocolHandler
import com.midokura.quagga.ZebraServer.RIBType


case class Request(socket: Socket, reqId: Int)
case class Response(reqId: Int)

protected[quagga] object ZebraProtocol {
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
    final val ZebraInterfaceAdd:Short           = 1
    final val ZebraInterfaceDelete:Short        = 2
    final val ZebraInterfaceAddressAdd:Short    = 3
    final val ZebraInterfaceAddressDelete:Short = 4
    final val ZebraInterfaceUp:Short            = 5
    final val ZebraInterfaceDown:Short          = 6
    final val ZebraIpv4RouteAdd:Short           = 7
    final val ZebraIpv4RouteDelete:Short        = 8
    final val ZebraIpv6RouteAdd:Short           = 9
    final val ZebraIpv6RouteDelete:Short        = 10
    final val ZebraRedistributeAdd:Short        = 11
    final val ZebraRedistributeDelete:Short     = 12
    final val ZebraRedistributeDefaultAdd:Short = 13
    final val ZebraRedistributeDefaultDelete:Short   = 14
    final val ZebraIpv4NextHopLookup:Short      = 15
    final val ZebraIpv6NextHopLookup:Short      = 16
    final val ZebraIpv4ImportLookup:Short       = 17
    final val ZebraIpv6ImportLookup:Short       = 18
    final val ZebraInterfaceRename:Short        = 19
    final val ZebraRouterIdAdd:Short            = 20
    final val ZebraRouterIdDelete:Short         = 21
    final val ZebraRouterIdUpdate:Short         = 22
    final val ZebraMessageMax:Short             = 23

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
        val length = in.readUnsignedShort
        assert(length <= ZebraMaxPayloadSize)
        val headerMarker = in.readByte
        val version = in.readByte
        val message = in.readUnsignedShort.toShort

        (message, length - ZebraHeaderSize)
    }
}

object ZebraConnection {
    import ZebraProtocol._

    // Midolman OpenvSwitch constants.
    // TODO(yoshi): get some values from config file.
    private final val OvsPortIdExtIdKey = "midolman_port_id"
    private final val OvsPortServiceExtIdKey = "midolman_port_service"
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
                val portUUID = ovsdb.synchronized {
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

                    /* TODO(pino): Do we need any of this?
                    // Add to router port's route if there is
                    // mapping. Ignored if not.
                    for (portUUID <- ribTypeToPortUUID.get(ribType)) {
                        val dstPrefix: Int = new IntIPv4(prefix).addressAsInt

                        val port = portMgr.synchronized {
                            portMgr.get(UUID.fromString(portUUID))
                        }
                        if (port.isInstanceOf[MaterializedRouterPortConfig]) {
                            val route = new Route(
                                0, 0, dstPrefix, prefixLen, Route.NextHop.PORT,
                                UUID.fromString(portUUID), addr,
                                AdvertisedWeight, "", port.device_id)
                            routeMgr.synchronized {
                                try {
                                    val routeUUID = routeMgr.create(route,
                                                                    false)
                                    zebraToRoute(advertised) = routeUUID
                                } catch {
                                    case e: StatePathExistsException =>
                                        { log.warn("node already exists") }
                                }
                            }
                        }
                    }*/
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

                    /* TODO(pino): do we need this anymore.
                    // Delete Route.
                    for (portUUID <- ribTypeToPortUUID.get(ribType)) {
                        if (zebraToRoute.contains(advertised)) {
                            val routeUUID = zebraToRoute(advertised)
                            routeMgr.synchronized {
                                try {
                                    routeMgr.delete(routeUUID)
                                } catch {
                                    case e: NoStatePathException =>
                                        { log.warn("no such node") }
                                }
                            }
                            zebraToRoute.remove(advertised)
                        }
                    }*/
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

object ZebraServer {
    object RIBType extends Enumeration {
        type RIBType = Value
        val RIP = Value("RIP")
        val OSPF = Value("OSPF")
        val ISIS = Value("ISIS")
        val BGP = Value("BGP")

        import ZebraProtocol._
        def fromInteger(ribType: Int): RIBType.Value = {
            ribType match {
                case ZebraRouteRip => return RIP
                case ZebraRouteBgp => return BGP
                case ZebraRouteOspf => return OSPF
                case ZebraRouteIsis => return ISIS
            }
        }
    }
}

trait ZebraServer {
    def start()
    def stop()
}

class ZebraServerImpl(val server: ServerSocket,
                      val address: SocketAddress,
                      val handler: ZebraProtocolHandler,
                      val ifAddr: IntIPv4,
                      val ifName: String)
    extends ZebraServer {

    private final val log = LoggerFactory.getLogger(this.getClass)
    private var run = false

    // Pool of Actors.
    val zebraConnPool = ListBuffer[ZebraConnection]()
    val zebraConnMap = mutable.Map[Int, ZebraConnection]()

    val dispatcher = actor {

        def addZebraConn(dispatcher: Actor) {
            val zebraConn =
                new ZebraConnection(dispatcher, handler, ifAddr, ifName)
            zebraConnPool += zebraConn
            zebraConn.start
        }

        loop {
            react {
                case Request(conn: Socket, requestId: Int) => {
                    if (zebraConnPool.isEmpty) {
                        addZebraConn(self)
                    }
                    val zebraConn = zebraConnPool.remove(0)
                    zebraConnMap(requestId) = zebraConn
                    zebraConn ! Request(conn, requestId)
                }
                case Response(requestId) => {
                    log.debug("dispatcher: response %d".format(requestId))
                    // Return the worker to the pool.
                    zebraConnMap.remove(requestId).foreach(zebraConnPool += _)
                }
                case _ =>
                    { log.error("dispatcher: unknown message received.") }
            }
        }
    }

    server.bind(address)


    override def start() {
        run = true
        log.info("start")

        actor {
            var requestId = 0
            loopWhile(run) {
                try {
                    val conn = server.accept
                    log.debug("start.actor accepted connection {}", conn)

                    dispatcher ! Request(conn, requestId)
                    requestId += 1
                } catch {
                    case e: IOException => {
                        log.error("accept failed", e)
                        run = false
                    }
                }
            }
        }
    }

    override def stop() {
        log.info("stop")
        run = false
        server.close()
    }
}
