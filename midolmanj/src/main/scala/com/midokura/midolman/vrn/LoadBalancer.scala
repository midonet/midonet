// Copyright 2012 Midokura Inc.

package com.midokura.midolman.vrn

import com.midokura.midolman.layer3.{Route, RoutingTable}
import com.midokura.midolman.openflow.MidoMatch


class LoadBalancer(val rTable: RoutingTable) {
    def lookup(mmatch: MidoMatch): Route = {
        null   //XXX
    }
}
