/*
 * Copyright 2012 Midokura Europe SARL
 */
package org.midonet.midolman.topology

import collection.{Map => ROMap, mutable}
import collection.JavaConversions._
import compat.Platform

import akka.event.LoggingAdapter
import akka.util.Duration

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


/* The MacFlowCount is called from the Coordinators' actors and dispatches
 * to the BridgeManager's actor to get/modify the flow counts.  */
trait MacFlowCount {
    def increment(mac: MAC, port: UUID): Unit
    def decrement(mac: MAC, port: UUID): Unit
}

trait RemoveFlowCallbackGenerator {
    def getCallback(mac: MAC, port: UUID): Callback0
}

case class BridgeConfig(tunnelKey: Int = 0,
                        inboundFilter: UUID = null,
                        outboundFilter: UUID = null)

object BridgeManager {
    val Name = "BridgeManager"

    case class TriggerUpdate(cfg: BridgeConfig,
                             macLearningTable: MacLearningTable,
                             ip4MacMap: IpMacMap[IPv4Addr],
                             macToLogicalPortId: ROMap[MAC, UUID],
                             ipToMac: ROMap[IPAddr, MAC],
                             vlanBridgePeerPortId: Option[UUID],
                             vlanPortMap: VlanPortMap)

    case class CheckExpiredMacPorts()

}

class MacLearningManager(log: LoggingAdapter, expirationMillis: Long) {
    var backendMap: MacLearningTable = null

    private val flowCountMap = mutable.Map[(MAC, UUID), Int]()
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
    private val macPortsToRemove = mutable.LinkedHashMap[(MAC, UUID), Long]()

    def incRefCount(mac: MAC, port: UUID): Unit = {
        flowCountMap.get((mac, port)) match {
            case None =>
                log.debug("First learning mac-port association. " +
                    "Incrementing reference count of {} on {} to 1",
                    mac, port)
                flowCountMap.put((mac, port), 1)
                backendMap.add(mac, port)
            case Some(i: Int) =>
                log.debug("Incrementing reference count of {} on {} to {}",
                    mac, port, i+1)
                flowCountMap.put((mac, port), i+1)
                if (i == 0) {
                    log.debug("Unscheduling removal of mac-port pair, mac {}, " +
                        "port {}.", mac, port)
                    macPortsToRemove.remove((mac, port))
                }
        }
    }

    def decRefCount(mac: MAC, port: UUID, currentTime: Long): Unit = {
        flowCountMap.get((mac, port)) match {
            case None =>
                log.error("Decrement flow count for unlearned mac-port " +
                    "{} {}", mac, port)
            case Some(i: Int) =>
                if (i <= 0) {
                    log.error("Decrement a flow count past {} " +
                        "for mac-port {} {}", i, mac, port)
                } else {
                    log.debug("Decrementing reference count of {} on {} " +
                        "to {}", mac, port, i-1)
                    flowCountMap.put((mac, port), i-1)
                    if (i == 1) {
                        log.debug("Scheduling removal of mac-port pair, mac {}, " +
                            "port {}.", mac, port)
                        macPortsToRemove.put((mac, port),
                            currentTime + expirationMillis)
                    }
                }
        }
    }

    def doDeletions(currentTime: Long): Unit = {
        log.debug("Size deleting {}", macPortsToRemove.size)
        val it: Iterator[((MAC, UUID), Long)] = macPortsToRemove.iterator
        while (it.hasNext) {
            val ((mac, port), expireTime) = it.next()
            if (expireTime <= currentTime) {
                log.debug("Forgetting mac-port entry {} {}", mac, port)
                backendMap.remove(mac, port)
                flowCountMap.remove((mac, port))
                macPortsToRemove.remove((mac, port))
            }
            else return
        }
    }
}

class BridgeManager(id: UUID, val clusterClient: Client,
                    val config: MidolmanConfig) extends DeviceManager(id) {
    import BridgeManager._
    implicit val system = context.system

    private var cfg: BridgeConfig = null

    private val flowCounts = new MacFlowCountImpl
    private val flowRemovedCallback = new RemoveFlowCallbackGeneratorImpl

    private var macToLogicalPortId: ROMap[MAC, UUID] = null
    private var rtrIpToMac: ROMap[IPAddr, MAC] = null
    private var ip4MacMap: IpMacMap[IPv4Addr] = null

    private var filterChanged = false
    private var vlanBridgePeerPortId: Option[UUID] = None

    private val macPortExpiration: Int = config.getMacPortMappingExpireMillis
    private val learningMgr = new MacLearningManager(
        log, config.getMacPortMappingExpireMillis)

    private var vlanToPort: VlanPortMap = null

    override def chainsUpdated() {
        log.info("chains updated")
        context.actorFor("..").tell(
            new Bridge(id, getTunnelKey, learningMgr.backendMap,
                if (config.getMidolmanBridgeArpEnabled) ip4MacMap
                else null,
                flowCounts, inFilter, outFilter,
                vlanBridgePeerPortId, flowRemovedCallback,
                macToLogicalPortId, rtrIpToMac, vlanToPort))
        if (filterChanged) {
            context.actorFor("..") ! FlowController.InvalidateFlowsByTag(
                FlowTagger.invalidateFlowsByDevice(id))
        }
        filterChanged = false
    }

    def getTunnelKey: Long = {
        cfg match {
            case null => 0
            case c => c.tunnelKey
        }
    }

    override def preStart() {
        clusterClient.getBridge(id, new BridgeBuilderImpl(id,
            FlowController.getRef(), self))
        // Schedule the recurring cleanup of expired mac-port associations.
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

    private case class FlowIncrement(mac: MAC, port: UUID)

    private case class FlowDecrement(mac: MAC, port: UUID)

    override def receive = super.receive orElse {

        case FlowIncrement(mac, port) =>
            learningMgr.incRefCount(mac, port)

        case FlowDecrement(mac, port) =>
            learningMgr.decRefCount(mac, port, Platform.currentTime)

        case CheckExpiredMacPorts() =>
            learningMgr.doDeletions(Platform.currentTime)

        case TriggerUpdate(newCfg, macLearningTable, newIp4MacMap,
                           newMacToLogicalPortId, newRtrIpToMac,
                           newVlanBridgePeerPortId, newVlanToPortMap) =>
            log.debug("Received a Bridge update from the data store.")
            if (newCfg != cfg && cfg != null) {
                // the cfg of this bridge changed, invalidate all the flows
                filterChanged = true
            }
            cfg = newCfg
            learningMgr.backendMap = macLearningTable
            ip4MacMap = newIp4MacMap
            macToLogicalPortId = newMacToLogicalPortId
            rtrIpToMac = newRtrIpToMac
            vlanBridgePeerPortId = newVlanBridgePeerPortId
            vlanToPort = newVlanToPortMap
            // Notify that the update finished
            configUpdated()
    }

    private class MacFlowCountImpl extends MacFlowCount {
        override def increment(mac: MAC, port: UUID) {
            self ! FlowIncrement(mac, port)
        }

        override def decrement(mac: MAC, port: UUID) {
            self ! FlowDecrement(mac, port)
        }
    }

    class RemoveFlowCallbackGeneratorImpl() extends RemoveFlowCallbackGenerator{
        def getCallback(mac: MAC, port: UUID): Callback0 = {
            new Callback0() {
                def call() {
                    // TODO(ross): check, is this the proper self, that is
                    // BridgeManager?  or it will be the self of the actor who
                    // execute this callback?
                    self ! FlowDecrement(mac, port)
                }
            }
        }
    }
}
