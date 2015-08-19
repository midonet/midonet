/*
 * Copyright 2015 Midokura SARL
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

import java.io.{PrintWriter, StringWriter}

import akka.actor.ActorRef

import java.net.InetSocketAddress
import java.nio.ByteOrder
import java.util.concurrent.Executors

import com.typesafe.scalalogging.Logger
import org.jboss.netty.bootstrap.ServerBootstrap
import org.jboss.netty.buffer.{ChannelBuffers, ChannelBuffer}
import org.jboss.netty.channel._
import org.jboss.netty.channel.socket.nio.NioServerSocketChannelFactory
import org.midonet.midolman.routingprotocols.RoutingHandler

import org.midonet.midolman.routingprotocols.RoutingHandler.{AddZebraPeerRoute, RemoveZebraPeerRoute}
import org.midonet.packets.{IPv4Addr, IPv4Subnet}

/*
 * This object creates a TCP server on a well-known port to handle push
 * notifications from zebra about its FIB. Whenever Zebra gets a route update
 * (for example, if bgpd learns a new route), then zebra will push that
 * notification to whoever is listening on this port.
 *
 * A notification is one or more FPM packets in a stream.
 *
 * An FPM packet is just a wrapper around a netlink packet.
 *
 * A netlink packet has basic routing information in well-known fields, plus
 * a variable sized list of Attributes.
 *
 * An attribute is just a couple of fields describing a block of data followed
 * by that block of data.
 */
class FpmServer(val port: Int, val routingHandler: ActorRef,
                val logger: Logger) {

    def logSource = s"org.midonet.routing.bgp.fpm-server-$port"

    // The netty socket message handler. This will handle all updates from
    // Zebra and notify the routing handler of route adds/removals
    var handler: FpmServerHandler = null
    var factory: ChannelFactory = null
    var channel: Channel = null

    def setupConnection(): Unit = {
        val connectionThreadPool = Executors.newCachedThreadPool()
        handler = new FpmServerHandler(routingHandler, routingHandler, port, logger)
        factory = new NioServerSocketChannelFactory(connectionThreadPool,
                                                    connectionThreadPool)

        val bootstrap: ServerBootstrap = new ServerBootstrap(factory)

        bootstrap.setPipelineFactory(new ChannelPipelineFactory {
            def getPipeline: ChannelPipeline = {
                Channels.pipeline(handler)
            }
        })

        logger.info("Starting FPM server on port: " + port)
        bootstrap.setOption("child.tcpNoDelay", true)
        bootstrap.setOption("child.keepAlive", true)
        channel = bootstrap.bind(new InetSocketAddress(port))
    }

    def destroyConnection(): Unit = {
        handler.notifyRouteRemovals()
        val future = channel.close()
        future.awaitUninterruptibly()
        factory.releaseExternalResources()
    }
}

class FpmServerHandler(val routingHandler: ActorRef, val fpmServer: ActorRef,
                       val port: Int, val logger: Logger)
    extends SimpleChannelHandler {

    // Just a class to hold the info that the routing handler cares about
    // so we can store it in a set
    case class ZebraRoute(destAddr: IPv4Subnet, gatewayAddr: IPv4Addr)

    // We keep track of the current zebra routes so that when the FPM server
    // is stopped, we can notify the routing handler about all the routes that
    // we need to remove.
    val zebraRoutes = scala.collection.mutable.Set[ZebraRoute]()

    // packet header sizes
    val NlHdrSize = 16
    val RtMsgHdrSize = 12

    // Address family we care about
    val AfInet = 2

    // Attribute types we care about
    val RtaDst = 1
    val RtaOif = 4
    val RtaGateway = 5
    val RtaPriority = 6
    val RtaMultipath = 8

    // operation types we care about
    val RtmNewRoute = 24
    val RtmDelRoute = 25

    // routing protocols we care about
    val RtProtUnpec = 0
    val RtProtZebra = 11

    def notifyRouteRemovals(): Unit = {
        for (route: ZebraRoute <- zebraRoutes) {
            routingHandler ! RemoveZebraPeerRoute(route.destAddr, route.gatewayAddr)
        }
    }

    override def channelConnected(ctx: ChannelHandlerContext,
                                  e: ChannelStateEvent): Unit = {
        logger.info("FPM server CONNECTED for port " + port)
    }

    override def channelDisconnected(ctx: ChannelHandlerContext,
                                     e: ChannelStateEvent): Unit = {
        logger.info("FPM server DISCONNECTED for port " + port)
        notifyRouteRemovals()
    }

    override def exceptionCaught(ctx: ChannelHandlerContext,
                                 e: ExceptionEvent): Unit = {
        val writer = new StringWriter
        val printWriter = new PrintWriter(writer)
        e.getCause.printStackTrace(printWriter)
        logger.error("Exception caught, close channel. " +
                     e.getCause.getMessage + ", " + writer.toString)
        routingHandler ! RoutingHandler.FPM_RECONNECT
    }

    override def messageReceived(ctx: ChannelHandlerContext,
                                 msg: MessageEvent) {
        /*
         * NOTE: The implementers of the Zebra FPM feature did *NOT* make the
         * netlink packet in network byte order. This forces us to assume
         * LITTLE_ENDIAN. This will be a problem on different architectures.
         */
        val stream: ChannelBuffer = ChannelBuffers
            .wrappedBuffer(ByteOrder.nativeOrder(),
                           msg.getMessage.asInstanceOf[ChannelBuffer].array)

        processFPMPacket(stream)
    }

    def processFPMPacket(packet: ChannelBuffer): Unit = {

        while (packet.readable) {
            processFPMHeader(packet)
            processNetlinkPacket(packet)
        }
    }

    /*
     * just move the index forward according to the values we know are there.
     */
    def processFPMHeader(packet: ChannelBuffer): Unit = {
        val version = packet.readByte()
        val typ = packet.readByte()
        val fpmLen = java.lang.Short.reverseBytes(
            packet.readUnsignedShort().toShort)
    }

    def processNetlinkPacket(packet: ChannelBuffer): Unit = {
        val startBytesRemaining = packet.readableBytes()
        val netlinkLength = packet.readInt
        assert(netlinkLength > NlHdrSize + RtMsgHdrSize)
        assert(netlinkLength <= startBytesRemaining)
        val netlinkMsgType = packet.readShort
        assert(netlinkMsgType == RtmDelRoute || netlinkMsgType == RtmNewRoute)
        val netlinkFlags = packet.readShort
        val netlinkSeq = packet.readInt
        val netlinkPid = packet.readInt

        // The packet index should have moved ahead exactly "length" bytes
        val endBytesRemaining = packet.readableBytes()
        val packetBytesRead = startBytesRemaining - endBytesRemaining
        assert(NlHdrSize == packetBytesRead)

        processRouteMsg(packet, netlinkMsgType, netlinkLength - NlHdrSize)
    }

    def processRouteMsg(packet: ChannelBuffer, msgType: Int,
                        dataLeft: Int): Unit = {
        val startBytesRemaining = packet.readableBytes()
        val routeFamily = packet.readByte
        val routeDstLen = packet.readByte
        val routeSrcLen = packet.readByte
        val routeTos = packet.readByte
        val routeTable = packet.readByte
        val routeProtocol = packet.readByte
        val routeScope = packet.readByte
        val routeType = packet.readByte
        val routeFlags = packet.readInt

        // Cases where the rtFamily is not AF_INET: when the route is IPv6
        if (routeFamily != AfInet) {
            logger.debug("ignoring update for route family " + routeFamily +
                         ", routeProtocol " + routeProtocol + ", msg type " +
                         msgType)
            packet.readBytes(dataLeft - RtMsgHdrSize)
            return
        }

        // Only add routes if the protocol is ZEBRA
        if (routeProtocol != RtProtZebra && msgType == RtmNewRoute) {
            logger.debug("ignoring update for route family " + routeFamily +
                         ", routeProtocol " + routeProtocol + ", msg type " +
                         msgType)
            packet.readBytes(dataLeft - RtMsgHdrSize)
            return
        }

        // Only delete routes where the protocol is unspecified
        if (routeProtocol != RtProtUnpec && msgType == RtmDelRoute) {
            logger.debug("ignoring update for route family " + routeFamily +
                         ", routeProtocol " + routeProtocol + ", msg type " +
                         msgType)
            packet.readBytes(dataLeft - RtMsgHdrSize)
            return
        }

        logger.debug("received update for route family " + routeFamily +
                     ", routeProtocol " + routeProtocol + ", msg type " +
                     msgType)

        assert(routeDstLen >= 0)
        assert(routeDstLen <= 32)
        // The packet index should have moved ahead exactly "length" bytes
        val endBytesRemaining = packet.readableBytes()
        val packetBytesRead = startBytesRemaining - endBytesRemaining
        assert(RtMsgHdrSize == packetBytesRead)

        processNetlinkAttributes(packet, msgType, routeDstLen,
                                 dataLeft - RtMsgHdrSize)
    }

    /*
     * This method takes a list of netlink attributes assumed to be in the
     * provided packet channel buffer. This is *NOT* a general netlink
     * attribute deserializer. Instead, it is assumed that the attribute list
     * comes from Zebra and contains the attributes we expect from zebra.
     */
    def processNetlinkAttributes(packet: ChannelBuffer, msgType: Int,
                                 routeDestLen: Int, length: Int): Unit = {
        var destAddr: IPv4Addr = null
        var gatewayAddr: IPv4Addr = null
        var dataLeft = length
        val startBytesRemaining = packet.readableBytes()

        while (dataLeft > 0) {
            val len = packet.readShort
            val attrType = packet.readShort

            def getAddrFromPacket() = IPv4Addr.fromBytes(
                Array(packet.readByte(), packet.readByte(), packet.readByte(),
                      packet.readByte()))

            attrType match {
                case RtaDst =>
                    val tmpDstAddr = getAddrFromPacket()
                    assert(dataLeft >= 8)
                    dataLeft -= 8 // known size of RtaDst option
                    if (destAddr != null) {
                        logger.error("2 Route Destinations in one list of " +
                                     "attributes. {} {}", destAddr, tmpDstAddr)
                        packet.readBytes(dataLeft)
                        return
                    }
                    destAddr = tmpDstAddr
                case RtaGateway =>
                    val tmpGatewayAddr = getAddrFromPacket()
                    assert(dataLeft >= 8)
                    dataLeft -= 8 // known size of RtaGateway option
                    if (gatewayAddr != null) {
                        logger.error("2 Route Destinations in one list of " +
                                     "attributes. {} {}", destAddr,
                                     tmpGatewayAddr)
                        packet.readBytes(dataLeft)
                        return
                    }
                    gatewayAddr = tmpGatewayAddr
                case _ =>
                    assert(dataLeft >= len)
                    packet.readBytes(len-4) // 4 bytes already read for len
                                            // and attribute type
                    dataLeft -= len
            }
        }

        // The packet index should have moved ahead exactly "length" bytes
        val endBytesRemaining = packet.readableBytes()
        val packetBytesRead = startBytesRemaining - endBytesRemaining
        assert(length == packetBytesRead)
        // dataLeft should be exactly 0
        assert(dataLeft == 0)

        sendRouteUpdate(destAddr, gatewayAddr, msgType, routeDestLen)
    }

    def sendRouteUpdate(destAddr: IPv4Addr, gatewayAddr: IPv4Addr,
                        msgType: Int, routeDestLen: Int): Unit = {
        (destAddr, gatewayAddr, msgType) match {
            case (dest, gate, RtmNewRoute) if dest != null && gate != null =>
                val routeDest = new IPv4Subnet(dest, routeDestLen)
                addZebraRoute(routeDest, gate)
            case (dest, gate, RtmDelRoute) if dest != null =>
                val routeDest = new IPv4Subnet(dest, routeDestLen)
                removeZebraRoute(routeDest)
            case _ => logger.warn("(dest, gate) is not valid: " + destAddr +
                                  ", " + gatewayAddr)
        }
    }

    def addZebraRoute(dest: IPv4Subnet, gate: IPv4Addr): Unit = {
        routingHandler ! AddZebraPeerRoute(dest, gate)
        zebraRoutes.add(ZebraRoute(dest, gate))
        logger.info("adding route " + dest + " -> " + gate)
    }

    def removeZebraRoute(dest: IPv4Subnet): Unit = {
        var zebraRoute: ZebraRoute = null
        for (route <- zebraRoutes) {
            if (route.destAddr == dest) {
                zebraRoute = route
            }
        }

        zebraRoutes.remove(zebraRoute)
        routingHandler ! RemoveZebraPeerRoute(dest, zebraRoute.gatewayAddr)
        logger.info("removing route " + dest + " -> " + zebraRoute.gatewayAddr)
    }
}
