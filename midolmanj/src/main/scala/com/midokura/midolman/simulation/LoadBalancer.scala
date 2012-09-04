// Copyright 2012 Midokura Inc.

package com.midokura.midolman.simulation

import com.midokura.midolman.layer3.{Route, RoutingTable}
import com.midokura.midolman.topology.RoutingTableWrapper
import com.midokura.sdn.flows.WildcardMatch


class LoadBalancer(val rTable: RoutingTableWrapper) {
    def lookup(mmatch: WildcardMatch): Route = {
        null //XXX
    }
}
