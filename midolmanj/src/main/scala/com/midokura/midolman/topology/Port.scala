/*
 * Copyright 2012 Midokura Europe SARL
 */
package com.midokura.midolman.topology

import java.util.UUID
import com.midokura.midolman.state.PortConfig
import com.midokura.packets.IntIPv4
import com.midokura.midolman.simulation.Chain

class Port(val id: UUID, val cfg: PortConfig,
           val inFilter: Chain, val outFilter: Chain) {
}

class MaterializedPort(id: UUID, cfg: PortConfig, inFilter: Chain,
                       outFilter: Chain, val locations: java.util.Set[IntIPv4])
    extends Port(id, cfg, inFilter, outFilter) {
    override def hashCode = id.hashCode()

    override def equals(other: Any) = other match {
        case that: MaterializedPort =>
            (that canEqual this) &&
                (this.id == that.id) && (this.cfg == that.cfg) && (this.inFilter == that.inFilter) &&
                (this.outFilter == that.outFilter) && (this.locations == that.locations)
        case _ =>
            false
    }

    def canEqual(other: Any) = other.isInstanceOf[MaterializedPort]
}
