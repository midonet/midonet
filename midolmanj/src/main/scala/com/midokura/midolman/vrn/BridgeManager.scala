/*
 * Copyright 2012 Midokura Europe SARL
 */
package com.midokura.midolman.vrn

import java.util.UUID
import com.midokura.midolman.state.BridgeZkManager
import com.midokura.midolman.state.BridgeZkManager.BridgeConfig

class BridgeManager(id: UUID, val mgr: BridgeZkManager)
    extends DeviceManager(id) {
    private var cfg: BridgeConfig = null;

    override def sendDeviceUpdate() = {
        context.actorFor("..").tell(new Bridge(id, cfg, inFilter, outFilter));
    }

    override def refreshConfig() = {
        //cfg = mgr.get(id, cb)
    }

    override def getOutFilterID() = {
        if (null == cfg)
            null;
        else
            cfg.outboundFilter
    }

    override def getInFilterID() = {
        if (null == cfg)
            null;
        else
            cfg.inboundFilter
    }
}