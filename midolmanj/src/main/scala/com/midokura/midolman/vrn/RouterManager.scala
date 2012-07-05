/*
 * Copyright 2012 Midokura Europe SARL
 */
package com.midokura.midolman.vrn

import java.util.UUID
import com.midokura.midolman.state.RouterZkManager
import com.midokura.midolman.state.RouterZkManager.RouterConfig

class RouterManager(id: UUID, val mgr: RouterZkManager)
    extends DeviceManager(id) {
    private var cfg: RouterConfig = null;

    override def sendDeviceUpdate() = {
        context.actorFor("..").tell(new Router(id, cfg, inFilter, outFilter));
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