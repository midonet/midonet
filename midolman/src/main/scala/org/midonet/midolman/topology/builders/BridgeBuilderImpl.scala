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

package org.midonet.midolman.topology.builders

import java.lang.{Short => JShort}
import java.util.{Map => JMap, Set => JSet, UUID}
import scala.Some
import scala.collection.JavaConversions._
import scala.collection.mutable

import akka.actor.ActorRef
import org.slf4j.LoggerFactory

import org.midonet.cluster.client.BridgeBuilder
import org.midonet.cluster.client.IpMacMap
import org.midonet.cluster.client.MacLearningTable
import org.midonet.cluster.client.VlanPortMap
import org.midonet.cluster.data.Bridge
import org.midonet.midolman.FlowController
import org.midonet.midolman.topology.BridgeConfig
import org.midonet.midolman.topology.BridgeManager
import org.midonet.packets.{IPv4Addr, IPAddr, MAC}
import org.midonet.sdn.flows.FlowTagger


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

    private var cfg = new BridgeConfig
    private val vlanMacTableMap: mutable.Map[JShort, MacLearningTable] =
        new mutable.HashMap[JShort, MacLearningTable]()
    private var ip4MacMap: IpMacMap[IPv4Addr] = null
    private var macToLogicalPortId: mutable.Map[MAC, UUID] = null
    private var oldMacToLogicalPortId: mutable.Map[MAC, UUID] = null
    private var ipToMac: mutable.Map[IPAddr, MAC] = null
    private var vlanBridgePeerPortId: Option[UUID] = None
    private var exteriorVxlanPortId: Option[UUID] = None
    private var vlanPortMap: VlanPortMap = null

    def setAdminStateUp(adminStateUp: Boolean) = {
        cfg = cfg.copy(adminStateUp = adminStateUp)
        this
    }

    def setTunnelKey(key: Long) {
        cfg = cfg.copy(tunnelKey = key.toInt)
    }

    def vlansInMacLearningTable(): JSet[JShort] = {
        setAsJavaSet(vlanMacTableMap.keys.toSet)
    }

    def removeMacLearningTable(vlanId: Short) {
        vlanMacTableMap.remove(vlanId)
    }

    def setMacLearningTable(vlanId: Short, table: MacLearningTable) {
        vlanMacTableMap.put(vlanId, table)
    }

    override def setIp4MacMap(m: IpMacMap[IPv4Addr]) {
        if (m != null) {
            ip4MacMap = m
            // TODO(pino): set the notification callback on this map?
        }
    }

    def setInFilter(filterID: UUID) = {
        cfg = cfg.copy(inboundFilter = filterID)
        this
    }

    def setVlanBridgePeerPortId(portId: Option[UUID]) {
        vlanBridgePeerPortId = portId
    }

    def setExteriorVxlanPortId(vxlanId: Option[UUID]) {
        exteriorVxlanPortId = vxlanId
    }

    def setVlanPortMap(map: VlanPortMap) {
        if (map != null) vlanPortMap = map
    }

    def setOutFilter(filterID: UUID) = {
        cfg = cfg.copy(outboundFilter = filterID)
        this
    }

    def start() = null

    def setLogicalPortsMap(newMacToLogicalPortId: JMap[MAC, UUID],
                           newIpToMac: JMap[IPAddr, MAC]) {
        oldMacToLogicalPortId = macToLogicalPortId
        macToLogicalPortId = newMacToLogicalPortId
        ipToMac = newIpToMac
    }

    /**
     * Computes a diff between old / new MAC to logical port ID mappings, and
     * send flow invalidation.
     */
    private def sendFlowInvalidation() {
        import collection.JavaConversions._
        log.debug("Diffing the maps.")
        // calculate diff between the 2 maps
        if (null != oldMacToLogicalPortId) {
            val deletedPortMap =
                oldMacToLogicalPortId -- (macToLogicalPortId.keys)
            // invalidate all the Unicast flows to the logical port
            for ((mac, portId) <- deletedPortMap) {
                flowController ! FlowController.InvalidateFlowsByTag(
                        FlowTagger.tagForBridgePort(id, portId))
            }
            // 1. Invalidate all arp requests
            // 2. Invalidate all flooded flows to the router port's specific MAC
            // We don't expect MAC migration in this case otherwise we'd be
            // invalidating Unicast flows to the device port's MAC
            val addedPortMap = macToLogicalPortId -- oldMacToLogicalPortId.keys
            if (addedPortMap.size > 0)
                flowController ! FlowController.InvalidateFlowsByTag(
                        FlowTagger.tagForArpRequests(id))

            for ((mac, portId) <- addedPortMap) {
                flowController ! FlowController.InvalidateFlowsByTag(
                        FlowTagger.tagForFloodedFlowsByDstMac(
                                id, Bridge.UNTAGGED_VLAN_ID, mac))
            }
        }
    }

    override def build() {
        log.debug("Building the bridge for {}", id)
        // send update info for bridges.
        // Convert the mutable map to immutable
        bridgeMgr ! BridgeManager.TriggerUpdate(cfg,
            collection.immutable.HashMap(vlanMacTableMap.toSeq: _*), ip4MacMap,
            collection.immutable.HashMap(macToLogicalPortId.toSeq: _*),
            collection.immutable.HashMap(ipToMac.toSeq: _*),
            vlanBridgePeerPortId, exteriorVxlanPortId, vlanPortMap)

         // Note from Guillermo:
         // There's a possible race here. We route flow invalidations through
         // the VTA to make sure there's a happens before relationship between device
         // updates and invalidations. Otherwise, a packet could get simulated with
         // the old version of the device _after_ the invalidation took place,
         // installing an obsolete flow that will skip invalidation.
         //
         // Not only that, but this invalidations need to go through the
         // BridgeManager, because it's that class that sends to the VTA. If we sent
         // the invalidation directly to the VTA from here we'd be incurring in the
         // same race.
         //
         // Here we are sending directly to the FlowController, that looks wrong.
         // TODO(tomohiko) Fix the possible race.
         sendFlowInvalidation()
    }

    override def updateMacEntry(
            vlanId: Short, mac: MAC, oldPort: UUID, newPort: UUID) = {
        log.debug("Mac-Port mapping for MAC {}, VLAN ID {} was updated from {} "
                  + "to {}",
                  Array(mac, vlanId.asInstanceOf[Object], oldPort, newPort))

        if (newPort == null && oldPort != null) {
            log.debug("MAC {}, VLAN ID {} removed from port {}",
                Array(mac, oldPort, vlanId.asInstanceOf[Object]))
            flowController ! FlowController.InvalidateFlowsByTag(
                    FlowTagger.tagForVlanPort(id, mac, vlanId, oldPort))
        }
        if (newPort != null && oldPort != null
            && !newPort.equals(oldPort)) {
            log.debug("MAC {}, VLAN ID {} moved from port {} to {}",
                      Array(mac, vlanId.asInstanceOf[Object], oldPort, newPort))
            flowController ! FlowController.InvalidateFlowsByTag(
                    FlowTagger.tagForVlanPort(id, mac, vlanId, oldPort))
        }
        if (newPort != null && oldPort == null){
            log.debug("MAC {}, VLAN ID {} added to port {}",
                      Array(mac, vlanId.asInstanceOf[Object], newPort))
            // Now we have the MAC entry in the table so we can deliver it to
            // the proper port instead of flooding it. As regards broadcast or
            // arp requests:
            // 1. If this port was just added to the PortSet, the invalidation
            //    will occur in the VirtualToPhysicalMapper.
            // 2. If we just forgot the MAC port association, no need of
            //    invalidating, the port was in the PortSet, broadcast and ARP
            //    requests were correctly delivered.
            flowController ! FlowController.InvalidateFlowsByTag(
                    FlowTagger.tagForFloodedFlowsByDstMac(id, vlanId, mac))
        }
    }

}

