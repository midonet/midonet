// Copyright 2012 Midokura Inc.

package com.midokura.midolman.simulation

import akka.dispatch.ExecutionContext
import com.midokura.sdn.flows.WildcardMatch
import com.midokura.packets.Ethernet

trait Device {
    // The ingressMatch contains the UUID of this device's ingress port.
    def process(ingressMatch: WildcardMatch,
                packet: Ethernet,
                coordinator: Coordinator,
                ec: ExecutionContext)
            : Coordinator.Action
}
