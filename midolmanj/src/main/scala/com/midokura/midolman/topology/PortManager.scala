/*
 * Copyright 2012 Midokura Europe SARL
 */
package com.midokura.midolman.topology

import java.util.UUID
import com.midokura.midolman.state.zkManagers.PortZkManager
import com.midokura.midolman.state.PortDirectory.{BridgePortConfig,
MaterializedRouterPortConfig, LogicalBridgePortConfig}
import com.midokura.packets.IntIPv4
import com.midokura.midolman.state.PortConfig

class PortManager(id: UUID, val mgr: PortZkManager,
                  val hostIp: IntIPv4) extends DeviceManager(id) {
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

    private def updateLocality(isLocal: Boolean): Unit = {
        if (local != isLocal) {
            local = isLocal
            if (local) {} // mgr.addLocation(id, hostIp)
            else {} //mgr.removeLocation(id, hostIp)
            if (chainsReady())
                makeNewPort()
            if (cfg.isInstanceOf[BridgePortConfig])
                context.actorFor("..").tell(
                    SetBridgePortLocal(cfg.device_id, id, isLocal))
            else
                context.actorFor("..").tell(
                    SetRouterPortLocal(cfg.device_id, id, isLocal))
        }
    }

    private def makeNewPort() {
        if (chainsReady()) {
            if (cfg.isInstanceOf[LogicalBridgePortConfig] ||
                cfg.isInstanceOf[MaterializedRouterPortConfig])
                context.actorFor("..").tell(
                    new Port(id, cfg, inFilter, outFilter))
            else if (null != locations)
                context.actorFor("..").tell(
                    new MaterializedPort(
                        id, cfg, inFilter, outFilter, locations))
        }
    }

    override def chainsUpdated = makeNewPort

    override def refreshConfig() = {
        cfg = mgr.get(id, cb)
    }

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
        case SetPortLocal(_, local) => updateLocality(local)
        case RefreshLocations => refreshLocations()
    }
}
