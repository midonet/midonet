/*
 * Copyright 2012 Midokura Pte. Ltd.
 */

package com.midokura.midolman.topology.builders

import com.midokura.midonet.cluster.client.{SourceNatResource, MacLearningTable, ForwardingElementBuilder, BridgeBuilder}
import java.util.{UUID}
import com.midokura.packets.{IntIPv4, MAC}
import com.midokura.util.functors.Callback3
import com.midokura.midolman.FlowController
import scala.collection.mutable.Map
import com.midokura.midolman.topology.{BridgeManager, BridgeConfig}
import akka.dispatch.Future
import akka.actor.{ActorContext, ActorRef}


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
        def call(mac: MAC, oldPort: UUID, newPort: UUID) {

            //1. MAC was deleted
            //2. the MAC moved from port-x to port-y
            //3. MAC was added (delete the flow for the flood, oldPort = null)
            flowController ! FlowController.InvalidateFlowByTag(
                (id, mac, oldPort))
        }
    }

}

