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

package org.midonet.midolman.topology.builders

import java.util.UUID

import akka.actor.ActorRef

import org.midonet.cluster.client.RouterBuilder
import org.midonet.midolman.layer3.{IPv4RoutingTable, Route}
import org.midonet.midolman.simulation.Router.Config
import org.midonet.midolman.state.ArpCache
import org.midonet.midolman.topology.RouterManager.{InvalidateFlows, TriggerUpdate}
import org.midonet.midolman.topology.RoutingTableWrapper

class RouterBuilderImpl(val id: UUID, val routerManager: ActorRef)
    extends RouterBuilder {

    private var cfg: Config = new Config
    private var arpCache: ArpCache = null
    private val routes = new scala.collection.mutable.HashSet[Route]()
    private val routesToAdd = new scala.collection.mutable.HashSet[Route]()
    private val routesToRemove = new scala.collection.mutable.HashSet[Route]()

    def setArpCache(table: ArpCache) {
        if (arpCache != null)
            throw new RuntimeException("RouterBuilder: attempt to re-set arp cache")
        if (table != null)
            arpCache = table
    }

    def addRoute(rt: Route) {
        routes.add(rt)
        routesToAdd.add(rt)
    }

    def removeRoute(rt: Route) {
        routes.remove(rt)
        routesToRemove.add(rt)
    }

    def setAdminStateUp(adminStateUp: Boolean) = {
        cfg = cfg.copy(adminStateUp = adminStateUp)
        this
    }

    def setInFilter(filterID: UUID) = {
        cfg = cfg.copy(inboundFilter = filterID)
        this
    }

    def setOutFilter(filterID: UUID) = {
        cfg = cfg.copy(outboundFilter = filterID)
        this
    }

    def setLoadBalancer(loadBalancerID: UUID) = {
        cfg = cfg.copy(loadBalancer = loadBalancerID)
    }

    def build() {
        // we always pass a new copy of the RoutingTable since this is accessed
        // by the RCU Router
        val table = new IPv4RoutingTable()
        for (rt <- routes)
            table.addRoute(rt)
        routerManager ! TriggerUpdate(cfg, arpCache, new RoutingTableWrapper(table))

        if (routesToAdd.size > 0 || routesToRemove.size > 0) {
            val added = routesToAdd.clone()
            val deleted = routesToRemove.clone()
            routerManager ! InvalidateFlows(id, added, deleted)
        }
        routesToAdd.clear()
        routesToRemove.clear()
    }

    def start() = null
}
