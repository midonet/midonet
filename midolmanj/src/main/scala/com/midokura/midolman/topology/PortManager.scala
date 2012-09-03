/*
 * Copyright 2012 Midokura Europe SARL
 */
package com.midokura.midolman.topology

import java.util.UUID
import com.midokura.midolman.state.PortDirectory.{BridgePortConfig,
MaterializedRouterPortConfig, LogicalBridgePortConfig}
import com.midokura.packets.IntIPv4
import com.midokura.midolman.state.PortConfig

class PortManager(id: UUID, val hostIp: IntIPv4) extends DeviceManager(id) {
    private var cfg: PortConfig = null
    private var local = false
    private var locations: java.util.Set[IntIPv4] = null
    refreshLocations()

    case object RefreshLocations

    val locCb: Runnable = new Runnable() {
        def run() {
            // CAREFUL: this is not run on this Actor's thread.
            self.tell(RefreshLocations)
        }
    }

    private def refreshLocations(): Unit = {
        locations = null; // mgr.getLocations(id, locCb)
        makeNewPort()
    }

    private def makeNewPort() {
        if (chainsReady()) {
            if (cfg.isInstanceOf[LogicalBridgePortConfig] ||
                cfg.isInstanceOf[MaterializedRouterPortConfig])
                context.actorFor("..").tell(
                    null) //new Port(id, cfg, inFilter, outFilter))
            else if (null != locations)
                context.actorFor("..").tell(
                    null) //new MaterializedPort(
                        //id, cfg, inFilter, outFilter, locations))
        }
    }

    override def chainsUpdated = makeNewPort


    /*
    //TODO(ross) use cluster client
    override def updateConfig() = {
        cfg = mgr.get(id, cb)
    } */

    override def getInFilterID() = {
        cfg match {
            case null => null;
            case _ => cfg.inboundFilter
        }
    }

    override def getOutFilterID() = {
        cfg match {
            case null => null;
            case _ => cfg.outboundFilter
        }
    }

    override def receive() = super.receive orElse {
        case RefreshLocations => refreshLocations()
    }
}
