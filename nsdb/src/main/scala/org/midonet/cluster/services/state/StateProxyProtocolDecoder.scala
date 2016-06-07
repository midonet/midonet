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

package org.midonet.cluster.services.state

import scala.util.control.NonFatal

import io.netty.buffer.ByteBuf
import io.netty.channel.ChannelHandler.Sharable
import io.netty.channel.ChannelHandlerContext
import io.netty.handler.codec.MessageToMessageDecoder

import org.midonet.cluster.rpc.State.Message

/**
  * Decodes a received [[ByteBuf]] into a State Proxy protocol [[Message]].
  */
@Sharable
class StateProxyProtocolDecoder extends MessageToMessageDecoder[ByteBuf] {

    @throws[Exception]
    protected override def decode(context: ChannelHandlerContext,
                                  message: ByteBuf,
                                  out: java.util.List[AnyRef]): Unit = {

        var array: Array[Byte] = null
        var offset = 0
        val length = message.readableBytes()

        if (message.hasArray) {
            array = message.array()
            offset = message.arrayOffset() + message.readerIndex()
        } else {
            array = new Array[Byte](length)
            message.getBytes(message.readerIndex(), array, 0, length)
            offset = 0
        }

        try {
            out.add(Message.getDefaultInstance.getParserForType
                                              .parseFrom(array, offset, length))
        } catch {
            case NonFatal(_) => out.add(message)
        }
    }

}
