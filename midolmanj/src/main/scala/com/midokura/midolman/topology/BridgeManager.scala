/*
 * Copyright 2012 Midokura Europe SARL
 */
package com.midokura.midolman.topology

import java.util.UUID

import com.midokura.midolman.simulation.Bridge
import com.midokura.midolman.state.zkManagers.BridgeZkManager
import com.midokura.midolman.state.zkManagers.BridgeZkManager.BridgeConfig
import com.midokura.midonet.cluster.client.MacLearningTable
import com.midokura.packets.MAC


/* The MacFlowCount is called from the Coordinators' actors and dispatches
 * to the VirtualTopologyActor to get/modify the flow counts.  */
trait MacFlowCount {
    def getCount(mac: MAC, port: UUID): Int
    def increment(mac: MAC, port: UUID): Unit
    def decrement(mac: MAC, port: UUID): Unit
}

class BridgeManager(id: UUID, val mgr: BridgeZkManager)
        extends DeviceManager(id) {
    private var cfg: BridgeConfig = null

    private val flowCounts: MacFlowCount = null         //XXX
    private val macPortMap: MacLearningTable = null     //XXX

    override def chainsUpdated() = {
        log.info("chains updated")
        context.actorFor("..").tell(
                new Bridge(id, cfg, macPortMap, flowCounts,
                           inFilter, outFilter))
    }

    override def refreshConfig() = {
        log.info("refresh config")
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
        case SetBridgePortLocal(_, portId, local) => // TODO XXX
    }
}
