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

import scala.collection.concurrent.TrieMap

import io.netty.channel.ChannelHandlerContext
import org.slf4j.LoggerFactory

class ConnectionManager {
    private val log = LoggerFactory.getLogger(classOf[ConnectionManager])

    private val channels: TrieMap[ChannelHandlerContext, Connection] =
        new TrieMap()

    /**
     * Get or register connection information
     */
    def register(ctx: ChannelHandlerContext): Connection = {
        val conn = new Connection(ctx)
        channels.putIfAbsent(ctx, conn) match {
            case Some(previous) => previous
            case None => conn
        }
    }

    /**
     * Forget about a connection
     */
    def unregister(ctx: ChannelHandlerContext): Unit = {
        channels.remove(ctx) match {
            case Some(connection) => connection.clear()
            case None =>
        }
    }
}
