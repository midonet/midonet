/*
 * Copyright 2012 Midokura Europe SARL
 */
package org.midonet.midolman.topology

import collection.{Map => ROMap, mutable}
import compat.Platform

import scala.concurrent.duration.Duration
import akka.event.LoggingAdapter

import java.lang.{Short => JShort}
import java.util.UUID
import java.util.concurrent.TimeUnit

import org.midonet.midolman.FlowController
import org.midonet.midolman.simulation.Bridge
import org.midonet.midolman.topology.builders.BridgeBuilderImpl
import org.midonet.cluster.Client
import org.midonet.cluster.client._
import org.midonet.packets.{IPv4Addr, IPAddr, MAC}
import org.midonet.util.functors.Callback0
import org.midonet.midolman.config.MidolmanConfig
import org.midonet.midolman.FlowController.InvalidateFlowsByTag

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
                             vlanPortMap: VlanPortMap)

    case class CheckExpiredMacPorts()

}

class MacLearningManager(log: LoggingAdapter, expirationMillis: Long) {
    var vlanMacTableMap: ROMap[JShort, MacLearningTable] = null

    private val vlanFlowCountMap = mutable.Map[(MAC, JShort, UUID), Int]()
    // Map mac-port pairs that need to be deleted to the time at which they
    // should be deleted. A map allows easy updates as the flowCounts change.
    // The ordering provided by the LinkedHashMap allows traversing in
    // insertion-order, which should be identical to the expiration order.
    // A mac-port pair will only be present this this map if it is also present
    // in the flowCountMap. The life-cycle of a mac-port pair is:
    // - When it's first learned, add ((mac,port), 1) to flowCountMap and
    //   write to to backendMap (which reflects distributed/shared map).
    // - When flows are added/removed, increment/decrement the flowCount
    // - If flowCount goes from 1 to 0, add (mac,port) to macPortToRemove
    // - If flowCount goes from 0 to 1, remove (mac,port) from macPortToRemove
    // - When iterating macPortToRemove, if the (mac,port) deletion time is
    //   in the past, remove it from macPortToRemove, flowCountMap and
    //   backendMap.
    private val vlanMacPortsToRemove = mutable.LinkedHashMap[(MAC, JShort, UUID), Long]()

    private def vlanMacTableOperation(vlanId: JShort, fun: MacLearningTable => Unit) {
        vlanMacTableMap.get(vlanId) match {
            case Some(map) => fun(map)
            case None => log.error("Mac learning table not found for VLAN {}", vlanId)
        }
    }

    private def addToMacTable(mac: MAC, vlanId: JShort, port: UUID) {
        vlanMacTableOperation(vlanId, (map: MacLearningTable) => map.add(mac, port))
    }

    private def removeFromMacTable(mac: MAC, vlanId: JShort, port: UUID) {
        vlanMacTableOperation(vlanId, (map: MacLearningTable) => map.remove(mac, port))
    }

    def incRefCount(mac: MAC, vlanId: JShort, port: UUID): Unit = {
        vlanFlowCountMap.get((mac, vlanId, port)) match {
            case None =>
                log.debug("First learning mac-port association. " +
                    "Incrementing reference count of {} on {}, vlan ID {}" +
                    " to 1", mac, port, vlanId)
                vlanFlowCountMap.put((mac, vlanId, port), 1)
                addToMacTable(mac, vlanId, port)
            case Some(i: Int) =>
                log.debug("Incrementing reference count of {} on {}, vlan ID" +
                    " {} to {}", mac, port, vlanId, i+1)
                vlanFlowCountMap.put((mac, vlanId, port), i+1)
                if (i == 0) {
                    log.debug("Unscheduling removal of mac-port pair," +
                        " mac {}, port {}, vlan ID {}.", mac, port, vlanId)
                    vlanMacPortsToRemove.remove((mac, vlanId, port))
                }
        }
    }

    def decRefCount(mac: MAC, vlanId: JShort, port: UUID,
                    currentTime: Long): Unit = {
        vlanFlowCountMap.get((mac, vlanId, port)) match {
            case None =>
                log.error("Decrement flow count for unlearned mac-port " +
                    "{} {}, vlan ID {}", mac, port, vlanId)
            case Some(i: Int) =>
                if (i <= 0) {
                    log.error("Decrement a flow count past {} " +
                        "for mac-port {} {}, vlan ID", i, mac, port, vlanId)
                } else {
                    log.debug("Decrementing reference count of {} on {}" +
                        ", vlan ID {} to {}", mac, port, vlanId, i-1)
                    vlanFlowCountMap.put((mac, vlanId, port), i-1)
                    if (i == 1) {
                        log.debug("Scheduling removal of mac-port pair, mac {}, " +
                            "port {}, vlan ID {}.", mac, port, vlanId)
                        vlanMacPortsToRemove.put((mac, vlanId, port),
                            currentTime + expirationMillis)
                    }
                }
        }
    }

    def doDeletions(currentTime: Long): Unit = {
        if (vlanMacPortsToRemove.isEmpty){
            return
        }

        log.debug("Found vlanMacPortsToRemove={}, currentTime={}",
                  vlanMacPortsToRemove, currentTime)

        val expiredEntries = vlanMacPortsToRemove.takeWhile
            {case (_, expireTime: Long) => expireTime <= currentTime}.toSeq

        expiredEntries.foreach {
                case ((mac, vlanId, port), _) => {
                    log.debug("Forgetting mac-port entry {} {}, vlan ID {}",
                        mac, port, vlanId)
                    removeFromMacTable(mac, vlanId, port)
                    vlanFlowCountMap.remove((mac, vlanId, port))
                    vlanMacPortsToRemove.remove((mac, vlanId, port))
                }
        }
    }
}

class BridgeManager(id: UUID, val clusterClient: Client,
                    val config: MidolmanConfig) extends DeviceManager(id) {
    import BridgeManager._
    implicit val system = context.system

    private var cfg: BridgeConfig = null
    private var changed = false

    private val flowCounts = new MacFlowCountImpl
    private val flowRemovedCallback = new RemoveFlowCallbackGeneratorImpl

    private var macToLogicalPortId: ROMap[MAC, UUID] = null
    private var rtrIpToMac: ROMap[IPAddr, MAC] = null
    private var ip4MacMap: IpMacMap[IPv4Addr] = null

    private var vlanBridgePeerPortId: Option[UUID] = None

    private val macPortExpiration: Int = config.getMacPortMappingExpireMillis
    private val learningMgr = new MacLearningManager(
        log, config.getMacPortMappingExpireMillis)

    private var vlanToPort: VlanPortMap = null

    override def chainsUpdated() {
        log.info("chains updated")
        VirtualTopologyActor.getRef() !
            new Bridge(id, isAdminStateUp, getTunnelKey,
                learningMgr.vlanMacTableMap,
                if (config.getMidolmanBridgeArpEnabled) ip4MacMap
                else null,
                flowCounts, inFilter, outFilter,
                vlanBridgePeerPortId, flowRemovedCallback,
                macToLogicalPortId, rtrIpToMac, vlanToPort)

        if (changed) {
            VirtualTopologyActor.getRef() !
                InvalidateFlowsByTag(FlowTagger.invalidateFlowsByDevice(id))
            changed = false
        }
    }

    def getTunnelKey: Long = {
        cfg match {
            case null => 0
            case c => c.tunnelKey
        }
    }

    override def isAdminStateUp: Boolean = {
        cfg match {
            case null => false
            case c => c.adminStateUp
        }
    }

    override def preStart() {
        clusterClient.getBridge(id, new BridgeBuilderImpl(id,
            FlowController.getRef(), self))
        // Schedule the recurring cleanup of expired mac-port associations.
        implicit val executor = context.dispatcher
        context.system.scheduler.schedule(
            Duration(macPortExpiration, TimeUnit.MILLISECONDS),
            Duration(2000, TimeUnit.MILLISECONDS), self, CheckExpiredMacPorts())
    }

    override def getInFilterID: UUID = {
        cfg match {
            case null => null
            case _ => cfg.inboundFilter
        }
    }

    override def getOutFilterID: UUID = {
        cfg match {
            case null => null
            case _ => cfg.outboundFilter
        }
    }

    private case class FlowIncrement(mac: MAC, vlanId: JShort, port: UUID)

    private case class FlowDecrement(mac: MAC, vlanId: JShort, port: UUID)

    override def receive = super.receive orElse {

        case FlowIncrement(mac, vlanId, port) =>
            learningMgr.incRefCount(mac, vlanId, port)

        case FlowDecrement(mac, vlanId, port) =>
            learningMgr.decRefCount(mac, vlanId, port, Platform.currentTime)

        case CheckExpiredMacPorts() =>
            learningMgr.doDeletions(Platform.currentTime)

        case TriggerUpdate(newCfg, vlanMacTableMap, newIp4MacMap,
                           newMacToLogicalPortId, newRtrIpToMac,
                           newVlanBridgePeerPortId, newVlanToPortMap) =>
            log.debug("Received a Bridge update from the data store.")

            if (newCfg != cfg && cfg != null)
                changed = true

            cfg = newCfg
            learningMgr.vlanMacTableMap = vlanMacTableMap
            ip4MacMap = newIp4MacMap
            macToLogicalPortId = newMacToLogicalPortId
            rtrIpToMac = newRtrIpToMac
            vlanBridgePeerPortId = newVlanBridgePeerPortId
            vlanToPort = newVlanToPortMap
            // Notify that the update finished
            configUpdated()
    }

    private class MacFlowCountImpl extends MacFlowCount {
        override def increment(mac: MAC, vlanId: JShort, port: UUID) {
            self ! FlowIncrement(mac, vlanId, port)
        }

        override def decrement(mac: MAC, vlanId: JShort, port: UUID) {
            self ! FlowDecrement(mac, vlanId, port)
        }
    }

    class RemoveFlowCallbackGeneratorImpl() extends RemoveFlowCallbackGenerator{
        def getCallback(mac: MAC, vlanId: JShort, port: UUID): Callback0 = {
            new Callback0() {
                def call() {
                    // TODO(ross): check, is this the proper self, that is
                    // BridgeManager?  or it will be the self of the actor who
                    // execute this callback?
                    self ! FlowDecrement(mac, vlanId, port)
                }
            }
        }
    }
}
