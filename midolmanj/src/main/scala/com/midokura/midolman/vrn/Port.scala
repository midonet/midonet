/*
 * Copyright 2012 Midokura Europe SARL
 */
package com.midokura.midolman.vrn

import java.util.UUID
import com.midokura.midolman.state.PortConfig

class Port(val id: UUID, val cfg: PortConfig,
           val inFilter: Chain, val outFilter: Chain) {

}
