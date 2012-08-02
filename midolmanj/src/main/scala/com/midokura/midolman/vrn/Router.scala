/*
 * Copyright 2012 Midokura Europe SARL
 */
package com.midokura.midolman.vrn

import java.util.UUID
import com.midokura.midolman.state.zkManagers.RouterZkManager
import com.midokura.midolman.layer3.RoutingTable
import com.midokura.midolman.state.zkManagers.RouterZkManager.RouterConfig

class Router(val id: UUID, val cfg: RouterConfig, val rTable: RoutingTable,
             val inFilter: Chain, val outFilter: Chain) {

}
