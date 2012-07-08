/*
 * Copyright 2012 Midokura Europe SARL
 */
package com.midokura.midolman.vrn

import java.util.UUID
import com.midokura.midolman.state.{PortConfig, PortZkManager}
import com.midokura.midolman.state.PortDirectory.BridgePortConfig
import collection.mutable
import com.midokura.midolman.packets.IntIPv4

class PortManager(id: UUID, val mgr: PortZkManager) extends DeviceManager(id) {
    private var cfg: PortConfig = null
    private var local = false
    private var needsLocationUpdate = false
    private val locations = mutable.Set[IntIPv4]()
    updateLocations()

    case object RefreshLocations
    val locCb: Runnable = new Runnable() {
        def run() {
            // CAREFUL: this is not run on this Actor's thread.
            self.tell(RefreshLocations)
        }
    }

    private def updateLocations(): Unit = {
        // TODO: mgr.getLocations(id, locCb)
    }

    private def updateLocality(isLocal: Boolean, ipAddr: IntIPv4): Unit = {
        if (local != isLocal) {
            local = isLocal
            if (null == cfg)
                needsLocationUpdate = true
            else {
                if (cfg.isInstanceOf[BridgePortConfig])
                    context.actorFor("..").tell(
                        SetBridgePortLocal(cfg.device_id, id, isLocal))
                else
                    context.actorFor("..").tell(
                        SetRouterPortLocal(cfg.device_id, id, isLocal))
                if (chainsReady())
                    makeNewPort()
                if (local) {
                    ; //TODO: mgr.addLocation(id, ipAddr)
                }
                else {
                    ; //TODO: mgr.removeLocation(id, ipAddr)
                }
            }
        }
    }

    private def makeNewPort(): Unit = {
        context.actorFor("..").tell(
            new Port(id, cfg, inFilter, outFilter, local));
    }

    override def chainsUpdated() = {
        if (!needsLocationUpdate)
            makeNewPort()
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
        case SetPortLocal(_, local, ipAddr) => updateLocality(local, ipAddr)
        case RefreshLocations => updateLocations()
    }
}
