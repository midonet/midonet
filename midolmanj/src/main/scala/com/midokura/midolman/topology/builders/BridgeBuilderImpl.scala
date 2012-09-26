/*
 * Copyright 2012 Midokura Pte. Ltd.
 */

package com.midokura.midolman.topology.builders

import akka.actor.ActorRef
import scala.collection.mutable.Map
import java.util.UUID

import com.midokura.midolman.FlowController
import com.midokura.midolman.topology.{BridgeConfig, BridgeManager, FlowTagger}
import com.midokura.midonet.cluster.client.{BridgeBuilder, MacLearningTable, SourceNatResource}
import com.midokura.packets.{IntIPv4, MAC}
import com.midokura.util.functors.Callback3
import org.slf4j.LoggerFactory


/**
 *  This class will be called by the Client to notify
 *  updates in the bridge configuration. Since it will be executed
 *  by the Client's thread, this class mustn't pass
 *  any modifiable object to the BridgeManager, otherwise it would
 *  break the actors model
 */

class BridgeBuilderImpl(val id: UUID, val flowController: ActorRef,
                        val bridgeMgr: ActorRef) extends BridgeBuilder {

    private val cfg: BridgeConfig = new BridgeConfig
    private var macPortMap: MacLearningTable = null
    private var rtrMacToLogicalPortId: Map[MAC, UUID] = null
    private var rtrIpToMac: Map[IntIPv4, MAC] = null


    def setTunnelKey(key: Long) {
        cfg.greKey = key.toInt
    }

    def setMacLearningTable(table: MacLearningTable) {
        // check if we should overwrite it
        if (table != null) {
            macPortMap = table
            macPortMap.notify(new MacTableNotifyCallBack)
        }
    }

    def setSourceNatResource(resource: SourceNatResource) {}

    def setID(id: UUID) = null //useless TODO(ross): delete it

    def setInFilter(filterID: UUID) = {
        cfg.inboundFilter = filterID
        this
    }

    def setOutFilter(filterID: UUID) = {
        cfg.outboundFilter = filterID
        this
    }

    def start() = null

    def setLogicalPortsMap(newRtrMacToLogicalPortId: java.util.Map[MAC, UUID],
                           newRtrIpToMac: java.util.Map[IntIPv4, MAC]) {
        import collection.JavaConversions._
        // invalidate all the flows if there's some change in the logical ports
        if(newRtrIpToMac != rtrIpToMac){
            flowController ! FlowController.InvalidateFlowsByTag(
                FlowTagger.invalidateFlowsByDevice(id))
        }
        rtrMacToLogicalPortId = newRtrMacToLogicalPortId
        rtrIpToMac = newRtrIpToMac
    }

    def build() {
        // send messages to the BridgeManager
        // Convert the mutable map to immutable
        bridgeMgr ! BridgeManager.TriggerUpdate(cfg, macPortMap,
            collection.immutable.HashMap(rtrMacToLogicalPortId.toSeq: _*),
            collection.immutable.HashMap(rtrIpToMac.toSeq: _*))
    }

    private class MacTableNotifyCallBack extends Callback3[MAC, UUID, UUID] {
        final val log =
            LoggerFactory.getLogger(classOf[MacTableNotifyCallBack])

        def call(mac: MAC, oldPort: UUID, newPort: UUID) {
            log.debug("Mac-Port mapping for MAC {} was updated from {} to {}",
                mac, oldPort, newPort)

            //1. MAC was removed from port
            if (newPort == null && oldPort != null) {
                flowController ! FlowController.InvalidateFlowsByTag(
                    FlowTagger.invalidateFlowsByPort(id, mac, oldPort))
            }
            //2. MAC moved from port-x to port-y
            if (newPort != null && oldPort != null) {
                flowController ! FlowController.InvalidateFlowsByTag(
                    FlowTagger.invalidateFlowsByPort(id, mac, oldPort))
            }
            // else: newPort != null && oldPort == null
            //3. MAC was added -> do nothing

            // in any case we need to re-compute the broadcast flows. If a port
            // was added, we have to include it in the broadcast, if a port was
            // deleted we have to remove it. If a port moved to a new host that
            // had no port of this bridge, we need a flow to the port that has
            // the tunnel to it
            // TODO(pino): I'm only invalidating flooded flows to that MAC.
            // TODO: so we need to invalidate all the flooded flows when the
            // TODO: hosts in the port set change or the local ports in the
            // TODO: PortSet change. This should be done in VTPM.
            flowController ! FlowController.InvalidateFlowsByTag(
                FlowTagger.invalidateFlowsByMac(id, mac))
        }
    }

    def setLocalExteriorPortActive(port: UUID, mac: MAC, active: Boolean) {
        // invalidate the flood flows in both cases (active/not active)
        flowController ! FlowController.InvalidateFlowsByTag(
            FlowTagger.invalidateBroadcastFlows(id))

        if (!active) {
            flowController ! FlowController.InvalidateFlowsByTag(
                FlowTagger.invalidateFlowsByMac(id, mac)
            )
        }
    }
}

