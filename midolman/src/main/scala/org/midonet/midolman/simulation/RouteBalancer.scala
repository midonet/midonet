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

import com.typesafe.scalalogging.Logger

import org.midonet.midolman.layer3.Route
import org.midonet.midolman.simulation.Router.RoutingTable
import org.midonet.odp.FlowMatch

/**
 * Handles lookups on the routing table. If multiple routes match, chooses
 * one based on the flow's hash.
 */
class RouteBalancer(val rTable: RoutingTable) extends AnyVal {

    def lookup(fmatch: FlowMatch, logger: Logger): Route = {
        val routes = rTable.lookup(fmatch, logger)
        routes.size match {
            case 0 => null
            case 1 =>
                logger.debug("routing to {}", routes.get(0))
                routes.get(0)
            case size =>
                val ret = routes.get(Math.abs(fmatch.connectionHash()) % size)
                logger.debug("got multiple routes: {}, selected {}",
                             routes, ret)
                ret
        }
    }
}
