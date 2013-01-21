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
import collection.mutable


/**
 *  This class will be called by the Client to notify
 *  updates in the bridge configuration. Since it will be executed
 *  by the Client's thread, this class mustn't pass
 *  any modifiable object to the BridgeManager, otherwise it would
 *  break the actors model
 */

class BridgeBuilderImpl(val id: UUID, val flowController: ActorRef,
                        val bridgeMgr: ActorRef) extends BridgeBuilder {
    final val log =
        LoggerFactory.getLogger(classOf[BridgeBuilderImpl])

    private val cfg: BridgeConfig = new BridgeConfig
    private var macPortMap: MacLearningTable = null
    private var rtrMacToLogicalPortId: Map[MAC, UUID] = null
    private var rtrIpToMac: Map[IntIPv4, MAC] = null


    def setTunnelKey(key: Long) {
        cfg.tunnelKey = key.toInt
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
        log.debug("Diffing the maps.")
        // calculate diff between the 2 maps
        if (null != rtrMacToLogicalPortId) {
            val deletedPortMap =
                rtrMacToLogicalPortId -- (newRtrMacToLogicalPortId.keys)
            // invalidate the flows of the deleted logical port
            for ((key, value) <- deletedPortMap){
                flowController !
                    FlowController.InvalidateFlowsByTag(
                        FlowTagger.invalidateFlowsByLogicalPort(id, value))
            }
        }
        rtrMacToLogicalPortId = newRtrMacToLogicalPortId
        rtrIpToMac = newRtrIpToMac
    }

    def build() {
        log.debug("Building the bridge for {}", id)
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
                Array(mac, oldPort, newPort))

            //1. MAC was removed from port
            if (newPort == null && oldPort != null) {
                flowController ! FlowController.InvalidateFlowsByTag(
                    FlowTagger.invalidateFlowsByPort(id, mac, oldPort))
            }
            //2. MAC moved from port-x to port-y
            if (newPort != null && oldPort != null && !newPort.equals(oldPort)) {
                flowController ! FlowController.InvalidateFlowsByTag(
                    FlowTagger.invalidateFlowsByPort(id, mac, oldPort))
            }
            //3. MAC was added -> invalidate flooded flows
            if (newPort != null && oldPort == null){
                flowController ! FlowController.InvalidateFlowsByTag(
                    FlowTagger.invalidateFloodedFlowsByMac(id, mac)
                )
            }

        }
    }

}

