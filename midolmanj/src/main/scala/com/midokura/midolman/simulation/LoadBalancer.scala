// Copyright 2012 Midokura Inc.

package com.midokura.midolman.simulation

import com.midokura.midolman.layer3.{Route, RoutingTable}
import com.midokura.midolman.openflow.MidoMatch
import com.midokura.midolman.topology.RoutingTableWrapper


class LoadBalancer(val rTable: RoutingTableWrapper) {
    def lookup(mmatch: MidoMatch): Route = {
        null //XXX
    }
}
