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

package org.midonet.brain.api.services

import io.netty.channel.ChannelHandlerContext
import org.slf4j.LoggerFactory

import org.midonet.cluster.rpc.Commands

/**
 * Connection channel
 */
class Connection(private val ctx: ChannelHandlerContext) {
    private val log = LoggerFactory.getLogger(classOf[Connection])
    private var client: Client = null

    def error(exc: Throwable): Unit = {
        log.error("topology server error", exc)
        // TODO: send error response to client
    }

    def msg(proto: Commands.Request): Unit = Command.parse(proto) match {
        case HandShake(req, tx) =>
        case Get(req) =>
        case Unsubscribe(req) =>
        case Bye(req) =>
        case InvalidCommand(_) =>
    }

    /**
     * Clean-up terminated connection
     */
    def clear(): Unit = {
        if (client != null) client.disconnect(ctx)
        client = null
        ctx.close()
    }
}
