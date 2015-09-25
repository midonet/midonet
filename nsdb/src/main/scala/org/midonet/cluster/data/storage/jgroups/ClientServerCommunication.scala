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

package org.midonet.cluster.data.storage.jgroups

import java.net.{InetSocketAddress, SocketAddress}
import java.util

import io.netty.buffer.ByteBuf
import io.netty.channel._
import io.netty.channel.socket.SocketChannel
import io.netty.handler.codec.{LengthFieldBasedFrameDecoder, ByteToMessageDecoder, MessageToByteEncoder}
import io.netty.handler.codec.serialization.{ClassResolvers, ObjectDecoder, ObjectEncoder}
import org.midonet.cluster.data.storage.jgroups.JGroupsBroker.PubSubMessage

object PubSubClientServerCommunication {
    def clientIdentifier(channel: Channel): String = {
        val tuple = channelHostPortTuple(channel)
        return tuple._1 + ":" + tuple._2
    }

    def channelHostPortTuple(channel: Channel): (String, Int) = {
        val addr: SocketAddress = channel.remoteAddress()
        channel.remoteAddress() match {
            case inetAddr:InetSocketAddress => (inetAddr.getAddress.getHostAddress, inetAddr.getPort)
            case  _ => throw new Exception("Cannot retrieve host and port from unknown channel type")
        }
    }
}

class PubSubChannelInitializer(receiver: PubSubMessageReceiver) extends ChannelInitializer[SocketChannel] {
    override def initChannel(ch: SocketChannel): Unit = {
        val pipeline = ch.pipeline()
        pipeline.addLast(
            new LengthFieldBasedFrameDecoder(1024, 0, 4),
            new PubSubEncoder(),
            new PubSubDecoder(),
            new PubSubInboundHandler(receiver)
        )
    }
}

class PubSubEncoder extends MessageToByteEncoder[PubSubMessage] {
    override def encode(ctx: ChannelHandlerContext, msg: PubSubMessage, out: ByteBuf): Unit = {
        val data = msg.toByteArray()
        out.writeInt(data.length)
        out.writeBytes(data)
    }
}

class PubSubDecoder extends ByteToMessageDecoder {
    override def decode(ctx: ChannelHandlerContext, in: ByteBuf, out: util.List[AnyRef]): Unit = {
        if (in.readableBytes() >= 4) {
            val len = in.readInt()
            val data = new Array[Byte](len)
            in.readBytes(data, 0, len)
            out.add(JGroupsBroker.toPubSubMessage(data))
        }
    }
}

trait PubSubMessageReceiver {
    def receive(channel: Channel, pubSubMessage: PubSubMessage)
    def connectionEstablished(channel: Channel)
}

class PubSubInboundHandler(receiver: PubSubMessageReceiver) extends SimpleChannelInboundHandler[PubSubMessage] {

    override def channelActive(ctx: ChannelHandlerContext): Unit = {
        receiver.connectionEstablished(ctx.channel())
    }

    override def channelReadComplete(ctx: ChannelHandlerContext): Unit = {
        ctx.flush()
    }

    override def exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable): Unit = {
        cause.printStackTrace()
        ctx.close()
    }

    override def channelRead0(ctx: ChannelHandlerContext, msg: PubSubMessage): Unit = {
        // Echo back the received object to the client.
        msg match {
            case pubSubMessage: PubSubMessage =>
                receiver.receive(ctx.channel(), pubSubMessage)

        }
    }
}