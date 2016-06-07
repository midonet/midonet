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

package org.midonet.cluster.services.state.server

import io.netty.channel.ChannelInitializer
import io.netty.channel.socket.SocketChannel

/**
  * Handles the initialization of the [[SocketChannel]] for new clients, which
  * consists in adding the following channel handlers to the channel pipeline:
  *
  * - A [[StateProxyProtocolDecoder]] that handles inbound byte buffers and
  *   decodes them into a Protocol Buffers protocol message.
  * - A [[StateProxyProtocolEncoder]] that handler outbound protocol messages
  *   and encodes them to a byte buffer.
  * - A [[StateProxyProtocolHandler]] that handles the request in the context
  *   of the server internal state machine.
  */
class StateProxyClientInitializer extends ChannelInitializer[SocketChannel] {

    @throws[Exception]
    override def initChannel(channel: SocketChannel): Unit = {
        channel.pipeline().addLast(new StateProxyProtocolDecoder,
                                   new StateProxyProtocolEncoder,
                                   new StateProxyProtocolHandler)
    }

}
