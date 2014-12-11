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

package org.midonet.cluster.services.topology.common

import scala.collection.concurrent.TrieMap
import scala.concurrent.duration.Duration
import scala.concurrent.{Await, Promise, Future}
import scala.util.Try

import io.netty.channel.ChannelHandlerContext
import org.slf4j.LoggerFactory

/**
 * Connector between the low-level netty channels and the API rpc
 * protocol state holder for each connection.
 * @param protocol is the protocol factory handling the communication for
 *                 the new connections.
 */
class ConnectionManager(protocol: ProtocolFactory) {
    private val log = LoggerFactory.getLogger(classOf[ConnectionManager])
    private val channels: TrieMap[ChannelHandlerContext, Future[Connection]] =
        new TrieMap()

    implicit val mgr: ConnectionManager = this

    /**
     * Register and retrieve connection state information
     */
    def get(ctx: ChannelHandlerContext): Connection = {
        /* The promise is always fulfilled when we insert the value in the
         * map, so the 'Await' should never block in normal conditions.
         * Using a promise allows us to defer the connection creation until
         * we confirm that we have to insert it in the map.
         */
        val conn = Promise[Connection]()
        channels.putIfAbsent(ctx, conn.future) match {
            case Some(previous) => Await.result(previous, Duration.Inf)
            case None => Await.result(
                conn.complete(Try(new Connection(ctx, protocol))).future,
                Duration.Inf)
        }
    }

    /**
     * Dismiss connection state data
     * Note: this should be called whenever the low-level channel between
     * client and service is severed.
     */
    def unregister(ctx: ChannelHandlerContext): Unit = channels.remove(ctx)
}
