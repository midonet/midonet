/*
 * Copyright 2012 Midokura Europe SARL
 */
package com.midokura.midolman.vrn

import java.util.UUID
import com.midokura.midolman.state.RouterZkManager.RouterConfig

class Router(val id: UUID, val cfg: RouterConfig,
             val inFilter: Chain, val outFilter: Chain) {

}
