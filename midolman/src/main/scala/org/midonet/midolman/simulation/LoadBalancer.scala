// Copyright 2012 Midokura Inc.

package org.midonet.midolman.simulation

import java.util.concurrent.atomic.AtomicLong

import org.midonet.midolman.layer3.Route
import org.midonet.midolman.topology.RoutingTableWrapper
import org.midonet.sdn.flows.WildcardMatch

class LoadBalancer(val rTable: RoutingTableWrapper) {
    val lookups: AtomicLong = new AtomicLong()

    def lookup(mmatch: WildcardMatch): Route = {
        val routes = rTable.lookup(mmatch)
        routes.size match {
            case 0 => null
            case 1 => routes.head
            case size =>
                val pos = (lookups.getAndIncrement % size).toInt
                routes.slice(pos, pos+1).head
        }
    }
}
