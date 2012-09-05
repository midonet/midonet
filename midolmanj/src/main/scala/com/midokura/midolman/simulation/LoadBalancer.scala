// Copyright 2012 Midokura Inc.

package com.midokura.midolman.simulation

import com.midokura.midolman.layer3.Route
import com.midokura.midolman.topology.RoutingTableWrapper
import com.midokura.sdn.flows.WildcardMatch


class LoadBalancer(val rTable: RoutingTableWrapper) {
    def lookup(mmatch: WildcardMatch): Route = {
        val routes = rTable.lookup(mmatch)
        if (routes.isEmpty)
            null
        else
            routes.head
    }
}
