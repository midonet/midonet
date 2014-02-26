/*
 * Copyright (c) 2014 Midokura Europe SARL, All Rights Reserved.
 */

package org.midonet.midolman.topology

import java.util.UUID
import org.midonet.midolman.topology.VirtualTopologyActor.DeviceRequest

trait DeviceWithChains extends TopologyPrefetcher {

    protected def cfg: { def inboundFilter: UUID
                         def outboundFilter: UUID }

    override def prefetchTopology(requests: DeviceRequest*) {
        super.prefetchTopology(requests ++ List(chain(cfg.inboundFilter),
                                                chain(cfg.outboundFilter)): _*)
    }
}
