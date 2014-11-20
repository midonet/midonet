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

import java.net.InetSocketAddress
import java.nio.ByteOrder
import java.util.concurrent.Executors
import org.jboss.netty.bootstrap.ServerBootstrap
import org.jboss.netty.buffer.{ChannelBuffers, ChannelBuffer}
import org.jboss.netty.channel._
import org.jboss.netty.channel.socket.nio.NioServerSocketChannelFactory

import akka.actor.{ActorContext, Props, ActorRef, Actor}
import akka.event.LoggingReceive

import org.midonet.midolman.logging.ActorLogWithoutPath
import org.midonet.midolman.routingprotocols.RoutingHandler.{AddPeerRoute, RemovePeerRoute}
import org.midonet.packets.{IPv4Addr, IPv4Subnet}
import org.slf4j.LoggerFactory

object FpmServer {
    def apply(port: Int, routingHandler: ActorRef) (implicit context: ActorContext): ActorRef = {
        context.actorOf(Props(new FpmServer(port, routingHandler))
                .withDispatcher("actors.pinned-dispatcher"), "fpm-server")
    }
}

/*
 * This Actor creates a TCP server on a well-known port to handle push
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
class FpmServer(val port: Int, val routingHandler: ActorRef)
    extends Actor with ActorLogWithoutPath {

    override def logSource = s"org.midonet.routing.bgp.fpm-server"

    // The netty socket message handler. This will handle all updates from
    // Zebra and notify the routing handler of route adds/removals
    val handler = new FpmServerHandler(routingHandler)

    override def preStart() {
        val factory: ChannelFactory = new NioServerSocketChannelFactory(
            Executors.newCachedThreadPool(),
            Executors.newCachedThreadPool())

        val bootstrap: ServerBootstrap = new ServerBootstrap(factory)

        bootstrap.setPipelineFactory(new ChannelPipelineFactory {
            def getPipeline: ChannelPipeline = {
                Channels.pipeline(handler)
            }
        })

        log.info("Starting FPM server on port: " + port)
        bootstrap.setOption("child.tcpNoDelay", true)
        bootstrap.setOption("child.keepAlive", true)
        bootstrap.bind(new InetSocketAddress(port))
    }

    override def postStop() {
        handler.notifyRouteRemovals()
        log.info("FPM Server on port " + port + " stopped")
    }

    override def receive = LoggingReceive {
        case m: AnyRef => log.error("Unknown message received - {}", m)
    }
}

class FpmServerHandler(val routingHandler: ActorRef)
    extends SimpleChannelHandler {

    // Just a class to hold the info that the routing handler cares about
    // so we can store it in a set
    class ZebraRoute(val destAddr: IPv4Subnet, val gatewayAddr: IPv4Addr)

    // We keep track of the current zebra routes so that when the FPM server
    // is stopped, we can notify the routing handler about all the routes that
    // we need to remove.
    val zebraRoutes = scala.collection.mutable.Set[ZebraRoute]()

    val log = LoggerFactory.getLogger(classOf[FpmServerHandler])

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
    val RtProtZebra = 11

    def notifyRouteRemovals(): Unit = {
        for (route: ZebraRoute <- zebraRoutes) {
            routingHandler ! RemovePeerRoute(route.destAddr, route.gatewayAddr)
        }
    }

    override def channelConnected(ctx: ChannelHandlerContext,
                                  e: ChannelStateEvent): Unit = {
        log.debug("FPM server CONNECTED")
    }

    override def channelDisconnected(ctx: ChannelHandlerContext,
                                     e: ChannelStateEvent): Unit = {
        log.debug("FPM server DISCONNECTED")
    }

    override def messageReceived(ctx: ChannelHandlerContext,
                                 msg: MessageEvent) {
        /*
         * NOTE: The implementers of the Zebra FPM feature did *NOT* make the
         * netlink packet in network byte order. This forces us to assume
         * LITTLE_ENDIAN. This will be a problem on different architectures.
         */
        val stream: ChannelBuffer = ChannelBuffers
            .wrappedBuffer(ByteOrder.LITTLE_ENDIAN,
                           msg.getMessage.asInstanceOf[ChannelBuffer].array)
        while (stream.readable) {
            processFPMPacket(stream)
        }
    }

    def processFPMPacket(packet: ChannelBuffer): Unit = {
        /*
         * 2 goals of this function:
         *
         * 1) Move the buffer index through the FPM to get to the start
         *    of the next FPM .
         * 2) Get the the packet route information contained in the packet.
         *    This is 4 pieces of information:
         *    1. The operation (delete the route OR add the route)
         *    2. The destination prefix
         *    3. The destination length
         *    4. The gateway address
         */

        val version = packet.readByte()
        val typ = packet.readByte()
        val fpmLen = java.lang.Short.reverseBytes(
            packet.readUnsignedShort().toShort)

        val nlLen = packet.readInt
        if (nlLen < NlHdrSize + RtMsgHdrSize) {
            throw new RuntimeException("Netlink msg size is too small: " +
                                       nlLen)
        }

        val nlMsgType = packet.readShort
        if (nlMsgType != RtmDelRoute && nlMsgType != RtmNewRoute) {
            throw new RuntimeException("Netlink msg type is not NEW_ROUTE " +
                                       "or DEL_ROUTE: " + nlLen)
        }

        val nlFlags = packet.readShort
        val nlSeq = packet.readInt
        val nlPid = packet.readInt

        val rtFamily = packet.readByte
        val rtDstLen = packet.readByte
        val rtSrcLen = packet.readByte
        val rtTos = packet.readByte
        val rtTable = packet.readByte
        val rtProtocol = packet.readByte
        val rtScope = packet.readByte
        val rtType = packet.readByte
        val rtFlags = packet.readInt
        log.debug("received route update: nlMsgType " + nlMsgType +
                  " rtType " + rtType + " rtProtocol " + rtProtocol +
                  " rtFamily " + rtFamily)

        var destAddr: IPv4Subnet = null
        var gatewayAddr: IPv4Addr = null

        var dataLeft = nlLen - NlHdrSize - RtMsgHdrSize

        // Cases where the rtFamily is not AF_INET: when the route is IPv6
        // Cases where the rtProtocol is not Zebra: when the route is a
        //   kernel route.
        if (rtFamily != AfInet || rtProtocol != RtProtZebra) {
            packet.readBytes(dataLeft)
            return
        }

        while (dataLeft > 0) {
            val len = packet.readShort
            val attrType = packet.readShort

            def getAddrByte = (packet.readUnsignedByte() & 0xFF).toByte

            attrType match {
                case RtaDst =>
                    destAddr = new IPv4Subnet(
                        IPv4Addr.fromBytes(Array(getAddrByte, getAddrByte,
                                                 getAddrByte, getAddrByte)),
                        rtDstLen)
                    dataLeft -= 8 // known size of RtaDst option
                case RtaGateway =>
                    gatewayAddr = IPv4Addr.fromBytes(Array(getAddrByte,
                                                           getAddrByte,
                                                           getAddrByte,
                                                           getAddrByte))
                    dataLeft -= 8 // known size of RtaGateway option
                case _ =>
                    packet.readBytes(len-4)
                    dataLeft -= len
            }
        }

        if (gatewayAddr == null) {
            // The gateway addr is not sent if it is the default route.
            gatewayAddr = IPv4Addr.fromBytes(Array(0.toByte, 0.toByte,
                                                   0.toByte, 0.toByte))
        }

        nlMsgType match {
            case RtmDelRoute =>
                routingHandler ! RemovePeerRoute(destAddr, gatewayAddr)
                zebraRoutes.remove(new ZebraRoute(destAddr, gatewayAddr))
            case RtmNewRoute =>
                routingHandler ! AddPeerRoute(destAddr, gatewayAddr)
                zebraRoutes.add(new ZebraRoute(destAddr, gatewayAddr))
            case _ =>
        }
    }
}
