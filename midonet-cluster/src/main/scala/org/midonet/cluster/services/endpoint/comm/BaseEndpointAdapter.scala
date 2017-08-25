/*
 * Copyright 2017 Midokura SARL
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

package org.midonet.cluster.services.endpoint.comm

import org.midonet.cluster.services.endpoint.registration.EndpointUserRegistrar
import org.midonet.util.netty.HTTPAdapter

import io.netty.channel.ChannelPipeline
import io.netty.channel.socket.SocketChannel
import io.netty.handler.ssl.SslContext

/**
  * Base adapter used by the endpoint.
  *
  * Enhances a HTTP channel with the HTTPPathBasedInitHandler.
  */
class BaseEndpointAdapter(userRegistrar: EndpointUserRegistrar,
                          sslCtx: Option[SslContext])
    extends HTTPAdapter(sslCtx) {

    override def initChannel(ch: SocketChannel) = {
        super.initChannel(ch)
        val pipe: ChannelPipeline = ch.pipeline()

        pipe.addLast("http-based-init",
                     new HTTPPathBasedInitHandler(userRegistrar))
    }
}
