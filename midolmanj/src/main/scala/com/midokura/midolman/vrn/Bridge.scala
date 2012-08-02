/*
 * Copyright 2012 Midokura Europe SARL
 */
package com.midokura.midolman.vrn

import java.util.UUID
import com.midokura.midolman.state.zkManagers.BridgeZkManager.BridgeConfig

class Bridge(val id: UUID, val cfg: BridgeConfig,
             val inFilter: Chain, val outFilter: Chain) {

}
