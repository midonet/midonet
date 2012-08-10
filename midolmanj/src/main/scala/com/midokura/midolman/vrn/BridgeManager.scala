/*
 * Copyright 2012 Midokura Europe SARL
 */
package com.midokura.midolman.vrn

import java.util.UUID
import com.midokura.midolman.state.zkManagers.BridgeZkManager
import com.midokura.midolman.state.zkManagers.BridgeZkManager.BridgeConfig

class BridgeManager(id: UUID, val mgr: BridgeZkManager)
    extends DeviceManager(id) {
    private var cfg: BridgeConfig = null;

    override def chainsUpdated() = {
        log.info("chains updated")
        context.actorFor("..").tell(new Bridge(id, cfg, inFilter, outFilter))
    }

    override def refreshConfig() = {
        log.info("refresh config")
        cfg = mgr.get(id, cb)
    }

    override def getInFilterID() = {
        cfg match { case null => null; case _ => cfg.inboundFilter }
    }

    override def getOutFilterID() = {
        cfg match { case null => null; case _ => cfg.outboundFilter }
    }

    override def receive() = super.receive orElse {
        case SetBridgePortLocal(_, portId, local) => ; // TODO
    }
}
