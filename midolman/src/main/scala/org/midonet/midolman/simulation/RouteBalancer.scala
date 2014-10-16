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

package org.midonet.midolman.simulation

import java.util.concurrent.atomic.AtomicLong

import com.typesafe.scalalogging.Logger

import org.midonet.midolman.layer3.Route
import org.midonet.midolman.topology.RoutingTableWrapper
import org.midonet.sdn.flows.WildcardMatch
import org.midonet.packets.IPAddr

/**
 * Handles lookups on the routing table. If multiple routes match, chooses
 * one in a pseudo-random way, to provide basic balancing.
 */
class RouteBalancer[IP <: IPAddr](val rTable: RoutingTableWrapper[IP]) {
    val lookups: AtomicLong = new AtomicLong()

    def lookup(mmatch: WildcardMatch, logger: Logger): Route = {
        val routes = rTable.lookup(mmatch)
        routes.size match {
            case 0 => null
            case 1 =>
                logger.debug("routing to {}", routes.head)
                routes.head
            case size =>
                val pos = (lookups.getAndIncrement % size).toInt
                val ret = routes.slice(pos, pos+1).head
                logger.debug("got multiple routes: {}, round robin to {}",
                             routes, ret)
                ret
        }
    }
}
