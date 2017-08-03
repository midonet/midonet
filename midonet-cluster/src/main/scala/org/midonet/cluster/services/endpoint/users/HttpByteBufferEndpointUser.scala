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

package org.midonet.cluster.services.endpoint.users

import org.midonet.cluster.services.endpoint.comm.{HttpByteBufferHandler, HttpByteBufferProvider, KeepAliveHandler}

import io.netty.channel.Channel

/**
  * Trait for those who want to have an endpoint with static file serving
  * capabilities.
  */
trait HttpByteBufferEndpointUser extends EndpointUser
                                         with HttpByteBufferProvider {

    import HttpByteBufferEndpointUser.DefaultIdleTimeout

    override def protocol(sslEnabled: Boolean): String =
        if (sslEnabled) "https" else "http"

    /**
      * Initialize static file serving handler.
      *
      * @param path    Path part of the URL of the first HTTP request handled.
      * @param channel Channel to be initialized.
      */
    override def initEndpointChannel(path: String, channel: Channel): Unit = {
        val pipe = channel.pipeline
        pipe.addLast("keep-alive", new KeepAliveHandler(DefaultIdleTimeout))
        pipe.addLast("http-bytebuffer-handler", new HttpByteBufferHandler(this))
    }
}

object HttpByteBufferEndpointUser {
    private final val DefaultIdleTimeout = 30000
}