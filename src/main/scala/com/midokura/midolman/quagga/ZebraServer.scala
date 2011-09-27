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

package com.midokura.midolman.quagga

import com.midokura.midolman.openvswitch.OpenvSwitchDatabaseConnection
import com.midokura.midolman.state.{PortZkManager, RouteZkManager}
import com.midokura.midolman.state.PortDirectory.MaterializedRouterPortConfig
import com.midokura.midolman.state.{NoStatePathException,
                                    StatePathExistsException}
import com.midokura.midolman.layer3.Route

import scala.actors._
import scala.actors.Actor._
import scala.collection.JavaConversions._
import scala.collection.mutable
import scala.collection.mutable.{ArrayBuffer, ListBuffer}

import java.io.{DataInputStream, DataOutputStream, EOFException, InputStream,
                IOException, OutputStream, OutputStreamWriter, PrintWriter}
import java.lang.StringBuffer
import java.net.{InetAddress, NetworkInterface, ServerSocket, Socket,
                 SocketAddress}
import java.nio.ByteBuffer
import java.util.UUID

import org.slf4j.LoggerFactory


case class Request(socket: Socket, reqId: Int)
case class Response(reqId: Int)

object ZebraConnection {
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

    // IP constants.
    private final val AF_INET  = 2
    private final val AF_INET6 = 10
    private final val Ipv4MaxBytelen = 4
    private final val Ipv6MaxBytelen = 16

    // Zebra protocol headers.
    private final val ZebraHeaderSize     = 6
    private final val ZebraHeaderMarker   = 255
    private final val ZebraHeaderVersion  = 1
    private final val ZebraMaxPayloadSize = (1 << 16) - 1

    // Zebra message types.
    private final val ZebraInterfaceAdd              = 1
    private final val ZebraInterfaceDelete           = 2
    private final val ZebraInterfaceAddressAdd       = 3
    private final val ZebraInterfaceAddressDelete    = 4
    private final val ZebraInterfaceUp               = 5
    private final val ZebraInterfaceDown             = 6
    private final val ZebraIpv4RouteAdd              = 7
    private final val ZebraIpv4RouteDelete           = 8
    private final val ZebraIpv6RouteAdd              = 9
    private final val ZebraIpv6RouteDelete           = 10
    private final val ZebraRedistributeAdd           = 11
    private final val ZebraRedistributeDelete        = 12
    private final val ZebraRedistributeDefaultAdd    = 13
    private final val ZebraRedistributeDefaultDelete = 14
    private final val ZebraIpv4NextHopLookup         = 15
    private final val ZebraIpv6NextHopLookup         = 16
    private final val ZebraIpv4ImportLookup          = 17
    private final val ZebraIpv6ImportLookup          = 18
    private final val ZebraInterfaceRename           = 19
    private final val ZebraRouterIdAdd               = 20
    private final val ZebraRouterIdDelete            = 21
    private final val ZebraRouterIdUpdate            = 22
    private final val ZebraMessageMax                = 23

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

    // Interface related constants.
    private final val InterfaceNameSize  = 20
    private final val InterfaceHwAddrMax = 20
    private final val MacAddrLength      = 6
    private final val ZebraInterfaceActive        = 0x01
    private final val ZebraInterfaceSub           = 0x02
    private final val ZebraInterfaceLinkdetection = 0x04

    // Zebra message payload sizes.
    // InterfaceNameSize + MacAddrLength + Long + Int * 6 + Byte
    private final val ZebraInterfaceAddSize =
        (InterfaceNameSize + MacAddrLength + 8 + 4 * 6 + 1).toByte
    // Ipv4MaxBytelen + Byte * 2
    private final val ZebraRouterIdUpdateSize = (Ipv4MaxBytelen + 1 * 2).toByte
    // Ipv4MaxBytelen + Int * 2 + Byte * 3
    private final val ZebraInterfaceAddressAddSize = (Ipv4MaxBytelen +
                                                      4 * 2 + 1 * 3).toByte

    // c.f. /usr/include/net/if.h
    private final val IFF_UP	      = 0x1
    private final val IFF_BROADCAST   = 0x2
    private final val IFF_DEBUG	      = 0x4
    private final val IFF_LOOPBACK    = 0x8
    private final val IFF_POINTOPOINT = 0x10
    private final val IFF_NOTRAILERS  = 0x20
    private final val IFF_RUNNING     = 0x40
    private final val IFF_NOARP	      = 0x80
    private final val IFF_PROMISC     = 0x100
    private final val IFF_ALLMULTI    = 0x200
    private final val IFF_MASTER      = 0x400
    private final val IFF_SLAVE	      = 0x800
    private final val IFF_MULTICAST   = 0x1000
    private final val IFF_PORTSEL     = 0x2000
    private final val IFF_AUTOMEDIA   = 0x4000
    private final val IFF_DYNAMIC     = 0x8000

    // Flags for connected address.
    private final val ZEBRA_IFA_SECONDARY = 0x01
    private final val ZEBRA_IFA_PEER      = 0x02

    // Zebra IPv4 route message API.
    private final val ZAPIMessageNextHop  = 0x01
    private final val ZAPIMessageIfIndex  = 0x02
    private final val ZAPIMessageDistance = 0x04
    private final val ZAPIMessageMetric   = 0x08

    // Zebra nexthop flags.
    private final val ZebraNexthopIfIndex     = 1
    private final val ZebraNextHopIfName      = 2
    private final val ZebraNextHopIpv4        = 3
    private final val ZebraNextHopIpv4IfIndex = 4
    private final val ZebraNextHopIpv4IfName  = 5
    private final val ZebraNextHopIpv6        = 6
    private final val ZebraNextHopIpv6IfIndex = 7
    private final val ZebraNextHopIpv6IfName  = 8
    private final val ZebraNextHopBlackhole   = 9

    // Zebra route types.
    private final val ZebraRouteSystem  = 0
    private final val ZebraRouteKernel  = 1
    private final val ZebraRouteConnect = 2
    private final val ZebraRouteStatic  = 3
    private final val ZebraRouteRip     = 4
    private final val ZebraRouteRipng   = 5
    private final val ZebraRouteOspf    = 6
    private final val ZebraRouteOspf6   = 7
    private final val ZebraRouteIsis    = 8
    private final val ZebraRouteBgp     = 9
    private final val ZebraRouteHsls    = 10
    private final val ZebraRouteMax     = 11

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

class ZebraConnection(val dispatcher: Actor, val portMgr: PortZkManager,
                      val routeMgr: RouteZkManager,
                      val ovsdb: OpenvSwitchDatabaseConnection) extends Actor {
    import ZebraConnection._

    // Map to get port uuid from route type.
    private val ribTypeToPortUUID = mutable.Map[Int, String]()
    // Map to track zebra route and MidoNet Route.
    private val zebraToRoute = mutable.Map[String, UUID]()

    implicit def inputStreamWrapper(in: InputStream) =
        new DataInputStream(in)

    implicit def outputStreamWrapper(out: OutputStream) =
        new DataOutputStream(out)

    def sendHeader(out: DataOutputStream, message: Byte, length: Int) = {
        assert(ZebraHeaderSize + length <= ZebraMaxPayloadSize)

        out.writeShort(ZebraHeaderSize + length)
        out.writeByte(ZebraHeaderMarker)
        out.writeByte(ZebraHeaderVersion)
        out.writeShort(message)
    }

    def interfaceAdd(out: DataOutputStream) = {
        val protocols = Array(BgpExtIdValue, OspfExtIdValue, RipfExtIdValue)
        var ifIndex = 0

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

                val portNode = portMgr.synchronized {
                    portMgr.get(UUID.fromString(portUUID))
                }
                val portConfig =
                    portNode.value.asInstanceOf[MaterializedRouterPortConfig]

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

                sendHeader(out, ZebraInterfaceAdd, ZebraInterfaceAddSize)

                val ifName = new StringBuffer(portName)
                ifName.setLength(InterfaceNameSize)
                out.writeBytes(ifName.toString)

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
                out.writeInt(portConfig.portAddr)
                out.write(portConfig.nwLength)
                // TODO(yoshi): fix dummy destination address.
                out.writeInt(0)

                log.info("ZebraInterfaceAddressAdd: index %d name %s addr %d"
                         .format(ifIndex, ifName, portConfig.portAddr))

                ifIndex += 1
            } else if (servicePort.nonEmpty) {
                log.warn("Only one service port for each protocol")
            }
        }
    }

    def routerIdUpdate(out: DataOutputStream) = {
        val ia = InetAddress.getLocalHost
        sendHeader(out, ZebraRouterIdUpdate, ZebraRouterIdUpdateSize)
        out.writeByte(AF_INET)
        out.write(ia.getAddress, 0, Ipv4MaxBytelen)
        // prefix length
        out.writeByte(32)
        log.info("ZebraRouterIdUpdate: %s".format(ia.getHostAddress))
    }

    def ipv4RouteAdd(in: DataInputStream, out: DataOutputStream) = {
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
                    // Add to router port's route if there is
                    // mapping. Ignored if not.
                    for (portUUID <- ribTypeToPortUUID.get(ribType)) {
                        val dstPrefix = prefix(0) << 24 |
                                        prefix(1) << 16 |
                                        prefix(2) << 8  |
                                        prefix(3)
                        val portNode = portMgr.synchronized {
                            portMgr.get(UUID.fromString(portUUID))
                        }
                        val port = portNode.value
                        if (port.isInstanceOf[MaterializedRouterPortConfig]) {
                            val route = new Route(
                                0, 0, dstPrefix, prefixLen, Route.NextHop.PORT,
                                UUID.fromString(portUUID), addr,
                                AdvertisedWeight, "", port.device_id)
                            routeMgr.synchronized {
                                try {
                                    val routeUUID = routeMgr.create(route)
                                    zebraToRoute(advertised) = routeUUID
                                } catch {
                                    case e: StatePathExistsException =>
                                        { log.warn("node already exists") }
                                }
                            }
                        }
                    }
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

    def ipv4RouteDelete(in: DataInputStream, out: DataOutputStream) = {
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
                    }
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

    def handleRequest(in: DataInputStream, out: DataOutputStream, id: Int) = {
        while (true) {
            val us = in.readUnsignedShort
            val header_marker = in.readByte
            val version = in.readByte
            val message = in.readUnsignedShort

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
                    log.error("received unknown message %d".format(message))
                }
            }
        }
    }

    def act() = {
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
                        conn.close
                        dispatcher ! Response(requestId)
                    }
                }
                case _ =>
                    { log.error("received unknown request") }
            }
        }
    }
}

class ZebraServer(val server: ServerSocket, val address: SocketAddress,
                  val portMgr: PortZkManager, val routeMgr: RouteZkManager,
                  val ovsdb: OpenvSwitchDatabaseConnection) {

    private final val log = LoggerFactory.getLogger(this.getClass)
    private var run = false

    // Pool of Actors.
    val zebraConnPool = ListBuffer[ZebraConnection]()
    val zebraConnMap = mutable.Map[Int, ZebraConnection]()

    val dispatcher = actor {

        def addZebraConn(dispatcher: Actor) = {
            val zebraConn = new ZebraConnection(dispatcher, portMgr, routeMgr,
                                                ovsdb)
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


    def start() = {
        run = true
        log.info("start")

        actor {
            var requestId = 0
            loopWhile(run) {
                try {
                    val conn = server.accept
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

    def stop() = {
        log.info("stop")
        run = false
        server.close
    }
}
