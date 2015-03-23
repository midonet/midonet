/*
 * Copyright 2014 Midokura SARL
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.midonet.midolman.topology

import java.lang.{Short => JShort}
import java.util
import java.util.UUID
import java.util.concurrent.TimeUnit

import scala.collection.{Map => ROMap}
import scala.compat.Platform
import scala.concurrent.duration._

import com.typesafe.scalalogging.Logger

import org.midonet.cluster.Client
import org.midonet.cluster.client._
import org.midonet.midolman.flows.FlowInvalidator
import org.midonet.midolman.topology.VirtualTopologyActor.InvalidateFlowsByTag
import org.midonet.midolman.config.MidolmanConfig
import org.midonet.midolman.simulation.Bridge
import org.midonet.midolman.topology.BridgeManager.MacPortMapping
import org.midonet.midolman.topology.builders.BridgeBuilderImpl
import org.midonet.packets.{IPAddr, IPv4Addr, MAC}
import org.midonet.util.collection.Reducer
import org.midonet.util.concurrent.TimedExpirationMap
import org.midonet.util.functors.Callback0

/* The MacFlowCount is called from the Coordinators' actors and dispatches
 * to the BridgeManager's actor to get/modify the flow counts.  */
trait MacFlowCount {
    def increment(mac: MAC, vlanId: Short, port: UUID): Unit
    def decrement(mac: MAC, vlanId: Short, port: UUID): Unit
}

trait RemoveFlowCallbackGenerator {
    def getCallback(mac: MAC,vlanId: Short, port: UUID): Callback0
}

case class BridgeConfig(adminStateUp: Boolean = true,
                        tunnelKey: Int = 0,
                        inboundFilter: UUID = null,
                        outboundFilter: UUID = null)

object BridgeManager {
    val Name = "BridgeManager"

    case class TriggerUpdate(cfg: BridgeConfig,
                             vlanMacTableMap: Map[Short, MacLearningTable],
                             ip4MacMap: IpMacMap[IPv4Addr],
                             macToLogicalPortId: ROMap[MAC, UUID],
                             ipToMac: ROMap[IPAddr, MAC],
                             vlanBridgePeerPortId: Option[UUID],
                             exteriorVxlanPortIds: Seq[UUID],
                             vlanPortMap: VlanPortMap,
                             exteriorPorts: List[UUID])

    case class CheckExpiredMacPorts()

    case class MacPortMapping(mac: MAC, vlan: JShort, port: UUID) {
        override def toString = s"{vlan=$vlan mac=$mac port=$port}"
    }

}

/**
 * Handles a bridge's mac-port associations. It add/removes the (MAC, VLAN, PORT)
 * tuples to/from the underlying replicated map. The callbacks guarantee
 * the required happens-before relationship because all zookeeper requests
 * are served by a single threaded reactor.
 */
class MacLearningManager(log: Logger, ttlMillis: Duration) {

    val map = new TimedExpirationMap[BridgeManager.MacPortMapping, AnyRef](log, _ => ttlMillis)

    @volatile var vlanMacTableMap: Map[Short, MacLearningTable] = null

    val reducer = new Reducer[BridgeManager.MacPortMapping, Any, Unit] {
        override def apply(acc: Unit, key: MacPortMapping, value: Any): Unit =
            vlanMacTableOperation(key.vlan, _.remove(key.mac, key.port))
    }

    private def vlanMacTableOperation(vlanId: JShort, fun: MacLearningTable => Unit) {
        vlanMacTableMap.get(vlanId) match {
            case Some(macLearningTable) => fun(macLearningTable)
            case None => log.warn(s"Mac learning table not found for VLAN $vlanId")
        }
    }

    def incRefCount(e: BridgeManager.MacPortMapping): Unit =
        if (map.putIfAbsentAndRef(e, e) eq null) {
            vlanMacTableOperation(e.vlan, _.add(e.mac, e.port))
        }

    def decRefCount(key: BridgeManager.MacPortMapping, currentTime: Long): Unit =
        map.unref(key, currentTime)

    def expireEntries(currentTime: Long): Unit =
        map.obliterateIdleEntries(currentTime, (), reducer)
}

class BridgeManager(id: UUID, val clusterClient: Client, flowInvalidator: FlowInvalidator,
                    val config: MidolmanConfig) extends DeviceWithChains {
    import context.system

import org.midonet.midolman.topology.BridgeManager._

    override def logSource = s"org.midonet.devices.bridge.bridge-$id"

    protected var cfg: BridgeConfig = null
    private var changed = false

    private val flowCounts = new MacFlowCountImpl
    private val flowRemovedCallback = new RemoveFlowCallbackGeneratorImpl

    private var macToLogicalPortId: ROMap[MAC, UUID] = null
    private var rtrIpToMac: ROMap[IPAddr, MAC] = null
    private var ip4MacMap: IpMacMap[IPv4Addr] = null

    private var vlanBridgePeerPortId: Option[UUID] = None
    private var exteriorVxlanPortIds = Seq.empty[UUID]

    private val macPortExpiration: Int = config.getMacPortMappingExpireMillis
    private val learningMgr = new MacLearningManager(
        log, config.getMacPortMappingExpireMillis millis)

    private var exteriorPorts: List[UUID] = List.empty

    private var vlanToPort: VlanPortMap = null

    def topologyReady() {
        val bridge = new Bridge(id, cfg.adminStateUp, cfg.tunnelKey,
            learningMgr.vlanMacTableMap,
            if (config.getMidolmanBridgeArpEnabled) ip4MacMap else null,
            flowCounts, Option(cfg.inboundFilter), Option(cfg.outboundFilter),
            vlanBridgePeerPortId, exteriorVxlanPortIds, flowRemovedCallback,
            macToLogicalPortId, rtrIpToMac, vlanToPort, exteriorPorts, flowInvalidator)

        VirtualTopologyActor ! bridge
        if (changed) {
            VirtualTopologyActor ! InvalidateFlowsByTag(bridge.deviceTag)
            changed = false
        }
    }

    override def preStart() {
        clusterClient.getBridge(id, new BridgeBuilderImpl(id, self))
        // Schedule the recurring cleanup of expired mac-port associations.
        implicit val executor = context.dispatcher
        context.system.scheduler.schedule(
            Duration(macPortExpiration, TimeUnit.MILLISECONDS),
            Duration(2000, TimeUnit.MILLISECONDS), self, CheckExpiredMacPorts())
    }

    override def receive = super.receive orElse {

        case CheckExpiredMacPorts() =>
            learningMgr.expireEntries(Platform.currentTime)

        case TriggerUpdate(newCfg, vlanMacTableMap, newIp4MacMap,
                           newMacToLogicalPortId, newRtrIpToMac,
                           newVlanBridgePeerPortId, newExteriorVxlanPortIds,
                           newVlanToPortMap, newExteriorPorts) =>
            log.debug("Received a Bridge update from the data store.")

            if (newCfg != cfg && cfg != null)
                changed = true

            cfg = newCfg
            learningMgr.vlanMacTableMap = vlanMacTableMap
            ip4MacMap = newIp4MacMap
            macToLogicalPortId = newMacToLogicalPortId
            rtrIpToMac = newRtrIpToMac
            vlanBridgePeerPortId = newVlanBridgePeerPortId
            exteriorVxlanPortIds = newExteriorVxlanPortIds
            vlanToPort = newVlanToPortMap
            exteriorPorts = newExteriorPorts
            // Notify that the update finished
            prefetchTopology()

        case invalidation: InvalidateFlowsByTag => VirtualTopologyActor ! invalidation
    }

    private class MacFlowCountImpl extends MacFlowCount {
        override def increment(mac: MAC, vlanId: Short, port: UUID) {
            learningMgr.incRefCount(MacPortMapping(mac, vlanId, port))
        }

        override def decrement(mac: MAC, vlanId: Short, port: UUID) {
            learningMgr.decRefCount(MacPortMapping(mac, vlanId, port),
                                    Platform.currentTime)
        }
    }

    class RemoveFlowCallbackGeneratorImpl() extends RemoveFlowCallbackGenerator{
        def getCallback(mac: MAC, vlanId: Short, port: UUID): Callback0 = {
            new Callback0() {
                override def call() {
                    learningMgr.decRefCount(MacPortMapping(mac, vlanId, port),
                                            Platform.currentTime)
                }
            }
        }
    }
}
