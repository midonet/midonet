/*
 * Copyright 2012 Midokura Europe SARL
 */
package com.midokura.midolman.vrn

import java.util.UUID
import com.midokura.midolman.state.RouterZkManager
import com.midokura.midolman.state.RouterZkManager.RouterConfig
import com.midokura.midolman.layer3.RoutingTable

class RouterManager(id: UUID, val mgr: RouterZkManager)
    extends DeviceManager(id) {
    private var cfg: RouterConfig = null;
    private var rTable: RoutingTable = null;

    override def chainsUpdated() = {
        context.actorFor("..").tell(
            new Router(id, cfg, rTable, inFilter, outFilter));
    }

    override def refreshConfig() = {
        cfg = mgr.get(id, cb)
    }

    override def getInFilterID() = {
        cfg match { case null => null; case _ => cfg.inboundFilter }
    }

    override def getOutFilterID() = {
        cfg match { case null => null; case _ => cfg.outboundFilter }
    }

    override def receive() = super.receive orElse {
        case SetRouterPortLocal(_, portId, local) => ; // TODO
    }
}