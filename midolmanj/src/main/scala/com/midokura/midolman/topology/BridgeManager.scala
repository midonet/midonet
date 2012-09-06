/*
 * Copyright 2012 Midokura Europe SARL
 */
package com.midokura.midolman.topology

import collection.{Map => ROMap, mutable}
import collection.JavaConversions._
import akka.actor.{Actor, ActorRef}
import akka.pattern.ask
import java.util.{Map, UUID}
import java.util.concurrent.ConcurrentHashMap

import com.midokura.midolman.FlowController
import com.midokura.midolman.simulation.Bridge
import com.midokura.midolman.topology.BridgeManager.TriggerUpdate
import com.midokura.midolman.topology.builders.BridgeBuilderImpl
import com.midokura.midonet.cluster.Client
import com.midokura.midonet.cluster.client._
import com.midokura.packets.{IntIPv4, MAC}
import com.midokura.util.functors.{Callback0, Callback1, Callback3}


/* The MacFlowCount is called from the Coordinators' actors and dispatches
 * to the BridgeManager's actor to get/modify the flow counts.  */
trait MacFlowCount {
    def getCount(mac: MAC, port: UUID): Int
    def increment(mac: MAC, port: UUID): Unit
    def decrement(mac: MAC, port: UUID): Unit
}

trait RemoveFlowCallbackGenerator {
    def getCallback(mac: MAC, port: UUID): Callback0
}

class BridgeConfig() {
    var greKey: Int = 0 // Only set in prepareBridgeCreate
    var inboundFilter: UUID = null
    var outboundFilter: UUID = null

    override def hashCode =
        (inboundFilter.toString + outboundFilter.toString).hashCode()

    override def equals(other: Any) = other match {
        case that: BridgeConfig =>
            (that canEqual this) &&
                (this.inboundFilter == that.inboundFilter) &&
                (this.outboundFilter == that.outboundFilter)
        case _ =>
            false
    }

    def canEqual(other: Any) = other.isInstanceOf[BridgeConfig]
}

object BridgeManager {
    val Name = "BridgeManager"

    case class TriggerUpdate(cfg: BridgeConfig,
                             macLearningTable: MacLearningTable,
                             rtrMacToLogicalPortId: ROMap[MAC, UUID],
                             rtrIpToMac: ROMap[IntIPv4, MAC])
}

//TODO(ross) watch and react to port added/deleted
//TODO(ross) handle portset?
class BridgeManager(id: UUID, val clusterClient: Client)
        extends DeviceManager(id) {
    private var cfg: BridgeConfig = null

    private var macPortMap: MacLearningTable = null
    private val flowCounts = new MacFlowCountImpl
    private val flowRemovedCallback = new RemoveFlowCallbackGeneratorImpl

    // Modified only by this actor, but read from the Simulation's too.
    private val flowCountMap: mutable.ConcurrentMap[(MAC, UUID), Int] =
        new ConcurrentHashMap[(MAC, UUID), Int]()

    private var rtrMacToLogicalPortId: ROMap[MAC, UUID] = null
    private var rtrIpToMac: ROMap[IntIPv4, MAC] = null
    
    private var filterChanged = false;


    override def chainsUpdated() {
        log.info("chains updated")
        context.actorFor("..").tell(
            new Bridge(id, macPortMap, flowCounts, inFilter, outFilter,
                       flowRemovedCallback, rtrMacToLogicalPortId, rtrIpToMac))
        if(filterChanged){
            FlowController.getRef() ! FlowController.InvalidateFlowsByTag(
            FlowTagger.invalidateAllDeviceFlowsTag(id))
        }
        filterChanged = false
    }

    override def preStart() {
        log.info("refresh config")
        clusterClient.getBridge(id, new BridgeBuilderImpl(id,
            FlowController.getRef(), self))
    }

    override def getInFilterID: UUID = {
        cfg match {
            case null => null;
            case _ => cfg.inboundFilter
        }
    }

    override def getOutFilterID: UUID = {
        cfg match {
            case null => null;
            case _ => cfg.outboundFilter
        }
    }

    private case class FlowIncrement(mac: MAC, port: UUID)

    private case class FlowDecrement(mac: MAC, port: UUID)

    override def receive = super.receive orElse {

        case FlowIncrement(mac, port) =>
            flowCountMap.get((mac, port)) match {
                case None =>
                    flowCountMap.put((mac, port), 1)
                    //XXX: Remove any delayed deletes for this MAC/port
                    //XXX: Check for migration from another port, and invalidate
                    //     flows to this MAC going to another port.
                case Some(i: Int) => flowCountMap.put((mac, port), i+1)
            }

        case FlowDecrement(mac, port) =>
            flowCountMap.get((mac, port)) match {
                case None =>
                    log.error("Decrement of nonexistant flow count {} {}",
                        mac, port)
                case Some(1) => {
                    flowCountMap.remove((mac, port))
                    macPortMap.remove(mac, port)
                }
                case Some(i: Int) => flowCountMap.put((mac, port), i-1)
            }

        case TriggerUpdate(newCfg, newMacLeaningTable, newRtrMacToLogicalPortId,
                           newRtrIpToMac) =>
            if (newCfg != cfg && cfg != null) {
                // the cfg of this bridge changed, invalidate all the flows
                filterChanged = true
            }
            cfg = newCfg
            macPortMap = newMacLeaningTable
            rtrMacToLogicalPortId = newRtrMacToLogicalPortId
            rtrIpToMac = newRtrIpToMac
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

        override def getCount(mac: MAC, port: UUID): Int = {
            flowCountMap.get((mac, port)) match {
                case None => 0
                case Some(i: Int) => i
            }
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
