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

import io.netty.buffer.Unpooled
import io.netty.channel.ChannelHandler.Sharable
import io.netty.channel.ChannelHandlerContext
import io.netty.handler.codec.MessageToMessageEncoder

import org.midonet.cluster.rpc.State.Message

/**
  * Encodes a State Proxy protocol [[Message]] into a sending
  * [[io.netty.buffer.ByteBuf]].
  */
@Sharable
class StateProxyProtocolEncoder extends MessageToMessageEncoder[Message] {

    protected override def encode(context: ChannelHandlerContext,
                                  message: Message,
                                  out: java.util.List[AnyRef]): Unit = {
        out.add(Unpooled.wrappedBuffer(message.toByteArray))
    }

}
