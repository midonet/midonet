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
import java.util
import java.util.{List => JList, Map => JMap, Set => JSet, UUID}

import scala.collection.JavaConversions._
import scala.collection.mutable

import akka.actor.ActorRef
import org.midonet.midolman.topology.VirtualTopologyActor.InvalidateFlowsByTag
import org.slf4j.LoggerFactory

import org.midonet.cluster.client.{VlanPortMap, BridgeBuilder, IpMacMap, MacLearningTable}
import org.midonet.cluster.data.Bridge
import org.midonet.midolman.topology.{BridgeConfig, BridgeManager}
import org.midonet.packets.{IPAddr, IPv4Addr, MAC}
import org.midonet.sdn.flows.FlowTagger

/**
 *  This class will be called by the Client to notify
 *  updates in the bridge configuration. Since it will be executed
 *  by the Client's thread, this class mustn't pass
 *  any modifiable object to the BridgeManager, otherwise it would
 *  break the actors model
 */

class BridgeBuilderImpl(val id: UUID, val bridgeMgr: ActorRef) extends BridgeBuilder {
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
    private var exteriorVxlanPortIds = Seq.empty[UUID]
    private var vlanPortMap: VlanPortMap = null
    private var exteriorPorts: List[UUID] = List.empty
    private var oldExteriorPorts: List[UUID] = List.empty

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

    def setExteriorVxlanPortIds(vxlanIds: util.List[UUID]) {
        exteriorVxlanPortIds = if (vxlanIds eq null) Seq.empty[UUID]
                               else vxlanIds
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
        log.debug("Diffing the maps.")
        // calculate diff between the 2 maps
        if (null != oldMacToLogicalPortId) {
            val deletedPortMap =
                oldMacToLogicalPortId -- macToLogicalPortId.keys
            // invalidate all the Unicast flows to the logical port
            for ((mac, portId) <- deletedPortMap) {
                bridgeMgr ! InvalidateFlowsByTag(
                        FlowTagger.tagForBridgePort(id, portId))
            }
            // 1. Invalidate all arp requests
            // 2. Invalidate all flooded flows to the router port's specific MAC
            // We don't expect MAC migration in this case otherwise we'd be
            // invalidating Unicast flows to the device port's MAC
            val addedPortMap = macToLogicalPortId -- oldMacToLogicalPortId.keys
            if (addedPortMap.size > 0)
                bridgeMgr ! InvalidateFlowsByTag(
                        FlowTagger.tagForArpRequests(id))

            for ((mac, portId) <- addedPortMap) {
                bridgeMgr ! InvalidateFlowsByTag(
                        FlowTagger.tagForFloodedFlowsByDstMac(
                                id, Bridge.UNTAGGED_VLAN_ID, mac))
            }
        }

        if (oldExteriorPorts != exteriorPorts) {
            bridgeMgr ! InvalidateFlowsByTag(FlowTagger.tagForBroadcast(id))
            oldExteriorPorts = exteriorPorts
        }
    }

    override def build() {
        log.debug("Building the bridge for {}", id)
        // send update info for bridges.
        // Convert the mutable map to immutable
        bridgeMgr ! BridgeManager.TriggerUpdate(cfg,
            collection.immutable.HashMap(
                vlanMacTableMap.toSeq.map(e => (Short.unbox(e._1), e._2)): _*),
            ip4MacMap,
            collection.immutable.HashMap(macToLogicalPortId.toSeq: _*),
            collection.immutable.HashMap(ipToMac.toSeq: _*),
            vlanBridgePeerPortId, exteriorVxlanPortIds, vlanPortMap,
            exteriorPorts)

         sendFlowInvalidation()
    }

    override def setExteriorPorts(ports: JList[UUID]) {
        exteriorPorts = asScalaBuffer(ports).toList
    }

    override def updateMacEntry(
            vlanId: Short, mac: MAC, oldPort: UUID, newPort: UUID) = {
        log.debug("Mac-Port mapping for MAC {}, VLAN ID {} was updated from {} "
                  + "to {}", mac, vlanId.asInstanceOf[Object], oldPort, newPort)

        if (newPort == null && oldPort != null) {
            log.debug("MAC {}, VLAN ID {} removed from port {}",
                Array(mac, oldPort, vlanId.asInstanceOf[Object]))
            bridgeMgr ! InvalidateFlowsByTag(
                    FlowTagger.tagForVlanPort(id, mac, vlanId, oldPort))
        }
        if (newPort != null && oldPort != null
            && !newPort.equals(oldPort)) {
            log.debug("MAC {}, VLAN ID {} moved from port {} to {}",
                      mac, vlanId.asInstanceOf[Object], oldPort, newPort)
            bridgeMgr ! InvalidateFlowsByTag(
                    FlowTagger.tagForVlanPort(id, mac, vlanId, oldPort))
        }
        if (newPort != null && oldPort == null){
            log.debug("MAC {}, VLAN ID {} added to port {}",
                      mac, vlanId.asInstanceOf[Object], newPort)
            // Now we have the MAC entry in the table so we can deliver it to
            // the proper port instead of flooding it. As regards broadcast or
            // arp requests:
            // 1. If this port was just added to the bridge, the invalidation
            //    will occur by the update to the bridge's list of ports.
            // 2. If we just forgot the MAC port association, no need of
            //    invalidating, broadcast and ARP requests were correctly
            //    delivered.
            bridgeMgr ! InvalidateFlowsByTag(
                    FlowTagger.tagForFloodedFlowsByDstMac(id, vlanId, mac))
        }
    }

}

