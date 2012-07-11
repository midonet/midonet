/*
 * Copyright 2012 Midokura Europe SARL
 */
package com.midokura.midolman.vrn

import java.util.UUID
import com.midokura.midolman.state.PortConfig
import com.midokura.midolman.packets.IntIPv4

class Port(val id: UUID, val cfg: PortConfig,
           val inFilter: Chain, val outFilter: Chain) {
}

class MaterializedPort(id: UUID, cfg: PortConfig, inFilter: Chain,
                       outFilter: Chain, val locations: java.util.Set[IntIPv4])
    extends Port(id, cfg, inFilter, outFilter) {

}
