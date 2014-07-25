/*
 * Copyright (c) 2013 Midokura SARL, All Rights Reserved.
 */
package org.midonet.midolman.topology

import collection.{Map => ROMap}
import compat.Platform
import java.lang.{Short => JShort}
import java.util.concurrent.TimeUnit
import java.util.UUID
import scala.concurrent.duration.Duration

import akka.event.LoggingAdapter

import org.midonet.cluster.Client
import org.midonet.cluster.client._
import org.midonet.midolman.FlowController
import org.midonet.midolman.FlowController.InvalidateFlowsByTag
import org.midonet.midolman.config.MidolmanConfig
import org.midonet.midolman.simulation.Bridge
import org.midonet.midolman.topology.builders.BridgeBuilderImpl
import org.midonet.packets.{IPv4Addr, IPAddr, MAC}
import org.midonet.util.concurrent.ConcurrentRefAccountant
import org.midonet.util.functors.Callback0

/* The MacFlowCount is called from the Coordinators' actors and dispatches
 * to the BridgeManager's actor to get/modify the flow counts.  */
trait MacFlowCount {
    def increment(mac: MAC, vlanId: JShort, port: UUID): Unit
    def decrement(mac: MAC, vlanId: JShort, port: UUID): Unit
}

trait RemoveFlowCallbackGenerator {
    def getCallback(mac: MAC,vlanId: JShort, port: UUID): Callback0
}

case class BridgeConfig(adminStateUp: Boolean = true,
                        tunnelKey: Int = 0,
                        inboundFilter: UUID = null,
                        outboundFilter: UUID = null)

object BridgeManager {
    val Name = "BridgeManager"

    case class TriggerUpdate(cfg: BridgeConfig,
                             vlanMacTableMap: ROMap[JShort, MacLearningTable],
                             ip4MacMap: IpMacMap[IPv4Addr],
                             macToLogicalPortId: ROMap[MAC, UUID],
                             ipToMac: ROMap[IPAddr, MAC],
                             vlanBridgePeerPortId: Option[UUID],
                             exteriorVxlanPortId: Option[UUID],
                             vlanPortMap: VlanPortMap)

    case class CheckExpiredMacPorts()

    case class MacPortMapping(mac: MAC, vlan: JShort, port: UUID) {
        override def toString = s"{vlan=$vlan mac=$mac port=$port}"
    }

}

/**
 * A ConcurrentRefAccountant for a bridge's mac-port associations.
 *
 * Its keys are (MAC, VLAN, PORT) tuples and it's add/delete callbacks
 * invoke add/remove on the underlying replicated map. The callbacks guarantee
 * the required happens-before relationship because all zookeeper requests
 * are served by a single threaded reactor.
 */
class MacLearningManager(override val log: LoggingAdapter,
        override val expirationMillis: Long) extends ConcurrentRefAccountant {
    @volatile var vlanMacTableMap: ROMap[JShort, MacLearningTable] = null

    override type Entry = BridgeManager.MacPortMapping

    private def vlanMacTableOperation(vlanId: JShort, fun: MacLearningTable => Unit) {
        vlanMacTableMap.get(vlanId) match {
            case Some(map) => fun(map)
            case None => log.error("Mac learning table not found for VLAN {}", vlanId)
        }
    }

    override def newRefCb(e: Entry) {
        vlanMacTableOperation(e.vlan, (map: MacLearningTable) => map.add(e.mac, e.port))
    }

    override def deletedRefCb(e: Entry) {
        vlanMacTableOperation(e.vlan, (map: MacLearningTable) => map.remove(e.mac, e.port))
    }
}

class BridgeManager(id: UUID, val clusterClient: Client,
                    val config: MidolmanConfig) extends DeviceWithChains {
    import BridgeManager._
    import context.system

    protected var cfg: BridgeConfig = null
    private var changed = false

    private val flowCounts = new MacFlowCountImpl
    private val flowRemovedCallback = new RemoveFlowCallbackGeneratorImpl

    private var macToLogicalPortId: ROMap[MAC, UUID] = null
    private var rtrIpToMac: ROMap[IPAddr, MAC] = null
    private var ip4MacMap: IpMacMap[IPv4Addr] = null

    private var vlanBridgePeerPortId: Option[UUID] = None
    private var exteriorVxlanPortId: Option[UUID] = None

    private val macPortExpiration: Int = config.getMacPortMappingExpireMillis
    private val learningMgr = new MacLearningManager(
        log, config.getMacPortMappingExpireMillis)

    private var vlanToPort: VlanPortMap = null

    def topologyReady() {
        log.debug("Sending a Bridge to the VTA")
        val bridge = new Bridge(id, cfg.adminStateUp, cfg.tunnelKey,
            learningMgr.vlanMacTableMap,
            if (config.getMidolmanBridgeArpEnabled) ip4MacMap else null,
            flowCounts, device(cfg.inboundFilter), device(cfg.outboundFilter),
            vlanBridgePeerPortId, exteriorVxlanPortId, flowRemovedCallback,
            macToLogicalPortId, rtrIpToMac, vlanToPort)

        VirtualTopologyActor ! bridge
        if (changed) {
            VirtualTopologyActor ! InvalidateFlowsByTag(bridge.deviceTag)
            changed = false
        }
    }

    override def preStart() {
        clusterClient.getBridge(id, new BridgeBuilderImpl(id,
            FlowController, self))
        // Schedule the recurring cleanup of expired mac-port associations.
        implicit val executor = context.dispatcher
        context.system.scheduler.schedule(
            Duration(macPortExpiration, TimeUnit.MILLISECONDS),
            Duration(2000, TimeUnit.MILLISECONDS), self, CheckExpiredMacPorts())
    }

    override def receive = super.receive orElse {

        case CheckExpiredMacPorts() =>
            learningMgr.doDeletions(Platform.currentTime)

        case TriggerUpdate(newCfg, vlanMacTableMap, newIp4MacMap,
                           newMacToLogicalPortId, newRtrIpToMac,
                           newVlanBridgePeerPortId, newExteriorVxlanPortId,
                           newVlanToPortMap) =>
            log.debug("Received a Bridge update from the data store.")

            if (newCfg != cfg && cfg != null)
                changed = true

            cfg = newCfg
            learningMgr.vlanMacTableMap = vlanMacTableMap
            ip4MacMap = newIp4MacMap
            macToLogicalPortId = newMacToLogicalPortId
            rtrIpToMac = newRtrIpToMac
            vlanBridgePeerPortId = newVlanBridgePeerPortId
            exteriorVxlanPortId = newExteriorVxlanPortId
            vlanToPort = newVlanToPortMap
            // Notify that the update finished
            prefetchTopology()
    }

    private class MacFlowCountImpl extends MacFlowCount {
        override def increment(mac: MAC, vlanId: JShort, port: UUID) {
            learningMgr.incRefCount(MacPortMapping(mac, vlanId, port))
        }

        override def decrement(mac: MAC, vlanId: JShort, port: UUID) {
            learningMgr.decRefCount(MacPortMapping(mac, vlanId, port),
                                    Platform.currentTime)
        }
    }

    class RemoveFlowCallbackGeneratorImpl() extends RemoveFlowCallbackGenerator{
        def getCallback(mac: MAC, vlanId: JShort, port: UUID): Callback0 = {
            new Callback0() {
                override def call() {
                    learningMgr.decRefCount(MacPortMapping(mac, vlanId, port),
                                            Platform.currentTime)
                }
            }
        }
    }
}
