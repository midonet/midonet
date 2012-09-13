/*
 * Copyright 2012 Midokura Pte. Ltd.
 */

package com.midokura.midolman.topology.builders

import java.util.UUID
import com.midokura.midonet.cluster.client.{ArpCache, SourceNatResource, RouterBuilder}
import akka.actor.{ActorRef, ActorContext}
import com.midokura.midolman.layer3.{RoutingTable, Route}
import com.midokura.midolman.topology.RouterManager.TriggerUpdate
import com.midokura.sdn.flows.WildcardMatch
import collection.immutable
import collection.mutable
import com.midokura.midolman.topology.{RoutingTableWrapper, RouterManager, RouterConfig}

/**
 * Copyright 2012 Midokura Europe SARL
 * User: Rossella Sblendido <rossella@midokura.com>
 * Date: 8/29/12
 */

class RouterBuilderImpl(val id: UUID, val routerManager: ActorRef)
    extends RouterBuilder {

    private val cfg: RouterConfig = new RouterConfig
    private var arpCache: ArpCache = null
    private val routes = new scala.collection.mutable.HashSet[Route]()

    def setArpCache(table: ArpCache) {
        if (arpCache != null)
            throw new RuntimeException("RouterBuilder: attempt to re-set arp cache")
        if (table != null)
            arpCache = table
    }

    def addRoute(rt: Route) {
        routes.add(rt)
    }

    def removeRoute(rt: Route) {
        routes.remove(rt)
    }

    def setSourceNatResource(resource: SourceNatResource) {}

    def setID(id: UUID) = null

    def setInFilter(filterID: UUID) = {
        cfg.inboundFilter = filterID
        this
    }

    def setOutFilter(filterID: UUID) = {
        cfg.outboundFilter = filterID
        this
    }

    def build() {
        val table = new RoutingTable()
        for (rt <- routes)
            table.addRoute(rt)
        routerManager ! TriggerUpdate(cfg, arpCache, new RoutingTableWrapper(table))
    }

    def start() = null

}
