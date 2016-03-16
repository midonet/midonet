/*
 * Copyright 2016 Midokura SARL
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

package org.midonet.cluster.services.flowstate

import com.typesafe.scalalogging.Logger
import io.netty.buffer.Unpooled
import io.netty.channel.socket.DatagramPacket
import io.netty.channel.{ChannelHandlerContext, SimpleChannelInboundHandler}
import org.slf4j.LoggerFactory

import org.midonet.cluster._
import org.midonet.midolman.state.FlowStateSbeEncoder
import org.midonet.packets.VXLAN
import org.midonet.util.netty.ClientFrontEnd

class FlowStateClient(host: String, port: Int) {

    private val log = Logger(LoggerFactory.getLogger(flowStateLog))

    private val client = new ClientFrontEnd(
        new FlowStateReplyHandler(log), host, port, true /* datagram */)
    client.startAsync().awaitRunning()

    def send(packet: VXLAN): Unit = {
        val msg = packet.serialize()
        client.send(Unpooled.copiedBuffer(msg))
    }

    def stop(): Unit = {
        client.stopAsync().awaitTerminated()
    }
}

class FlowStateReplyHandler(log: Logger)
    extends SimpleChannelInboundHandler[DatagramPacket] {

    val flowStateSbeEncoder = new FlowStateSbeEncoder()

    override def channelRead0(ctx: ChannelHandlerContext,
                              msg: DatagramPacket): Unit = {
        log info s"Received: ${msg.content()}"
    }

    override def channelReadComplete(ctx: ChannelHandlerContext) {
        ctx.flush()
    }

    override def exceptionCaught(xtx: ChannelHandlerContext, cause: Throwable): Unit = {
        cause.printStackTrace()
        // We don't close the channel because we can keep serving requests.
    }
}