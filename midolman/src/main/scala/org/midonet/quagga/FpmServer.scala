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
import com.typesafe.scalalogging.Logger
import org.jboss.netty.bootstrap.ServerBootstrap
import org.jboss.netty.buffer.{ChannelBuffers, ChannelBuffer}
import org.jboss.netty.channel._
import org.jboss.netty.channel.socket.nio.NioServerSocketChannelFactory

import akka.actor.{ActorContext, Props, ActorRef, Actor}
import akka.event.LoggingReceive

import org.midonet.midolman.logging.ActorLogWithoutPath
import org.midonet.midolman.routingprotocols.RoutingHandler.{AddPeerRoute, RemovePeerRoute}
import org.midonet.packets.{IPv4Addr, IPv4Subnet}

object FpmServer {
    def apply(port: Int, routingHandler: ActorRef) (implicit context: ActorContext): ActorRef = {
        context.actorOf(Props(new FpmServer(port, routingHandler))
                .withDispatcher("actors.pinned-dispatcher"), "fpm-server")
    }
}

class FpmServer(val port: Int, val routingHandler: ActorRef) extends Actor with ActorLogWithoutPath {

    override def logSource = s"org.midonet.routing.bgp.fpm-server"

    override def preStart() {
        val factory: ChannelFactory = new NioServerSocketChannelFactory(
            Executors.newCachedThreadPool(),
            Executors.newCachedThreadPool())

        val bootstrap: ServerBootstrap = new ServerBootstrap(factory)

        bootstrap.setPipelineFactory(new ChannelPipelineFactory {
            def getPipeline: ChannelPipeline = {
                Channels.pipeline(new FpmServerHandler(routingHandler, log))
            }
        })

        log.debug("Starting FPM server on port: {}", port)
        bootstrap.setOption("child.tcpNoDelay", true)
        bootstrap.setOption("child.keepAlive", true)
        bootstrap.bind(new InetSocketAddress(port))
    }

    override def postStop() {
        log.debug("Server stopped")
    }

    override def receive = LoggingReceive {
        case m: AnyRef => log.error("Unknown message received - {}", m)
    }
}

class FpmServerHandler(val routingHandler: ActorRef, val log: Logger)
    extends SimpleChannelHandler {

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


    override def messageReceived(ctx: ChannelHandlerContext,
                                 msg: MessageEvent) {
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
         * 2) Get the the packet route information contained in the packet. This is
         *    4 pieces of information:
         *    - The operation (delete the route OR add the route)
         *    - The destination prefix
         *    - The destination length
         *    - The gateway address
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
        log.debug("received route update: nlMsgType {}, rtType {}, rtProtocol {}, rtFamily {}", nlMsgType, rtType, rtProtocol, rtFamily)

        var destAddr: IPv4Subnet = null
        var gatewayAddr: IPv4Addr = null

        var dataLeft = nlLen - NlHdrSize - RtMsgHdrSize

        if (rtFamily != AfInet) {
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
                    dataLeft -= 8
                case RtaGateway =>
                    gatewayAddr = IPv4Addr.fromBytes(Array(getAddrByte,
                                                           getAddrByte,
                                                           getAddrByte,
                                                           getAddrByte))
                    dataLeft -= 8
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
            case RtmNewRoute =>
                routingHandler ! AddPeerRoute(destAddr, gatewayAddr)
            case _ =>
        }
    }
}