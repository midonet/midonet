/*
 * Copyright 2012 Midokura Europe SARL
 */
package com.midokura.midolman.vrn

import java.util.UUID
import com.midokura.midolman.state.{PortConfig, PortZkManager}

case object Refresh

class PortManager(id: UUID, val mgr: PortZkManager)
    extends DeviceManager(id) {
    private var cfg: PortConfig = null;

    override def sendDeviceUpdate() = {
        context.actorFor("..").tell(new Port(id, cfg, inFilter, outFilter));
    }

    override def refreshConfig() = {
        cfg = mgr.get(id, cb)
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
