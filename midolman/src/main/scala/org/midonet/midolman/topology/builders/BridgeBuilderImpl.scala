/*
 * Copyright 2012 Midokura Pte. Ltd.
 */

package org.midonet.midolman.topology.builders

import akka.actor.ActorRef
import collection.mutable
import java.util.{Map => JMap, Set => JSet, UUID}

import org.slf4j.LoggerFactory

import org.midonet.cluster.client._
import org.midonet.cluster.data.Bridge
import org.midonet.midolman.FlowController
import org.midonet.midolman.topology._
import org.midonet.packets.{IPv4Addr, IPAddr, MAC}
import org.midonet.util.functors.Callback3
import java.lang.{Short => JShort}
import collection.JavaConversions._
import scala.Some
import org.midonet.midolman.topology.BridgeConfig


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
    private var ipToMac: mutable.Map[IPAddr, MAC] = null
    private var vlanBridgePeerPortId: Option[UUID] = None
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
        table.notify(new MacTableNotifyCallBack(vlanId))
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
        import collection.JavaConversions._
        log.debug("Diffing the maps.")
        // calculate diff between the 2 maps
        if (null != macToLogicalPortId) {
            val deletedPortMap =
                macToLogicalPortId -- (newMacToLogicalPortId.keys)
            // invalidate all the Unicast flows to the logical port
            for ((mac, portId) <- deletedPortMap) {
                flowController !
                    FlowController.InvalidateFlowsByTag(
                        FlowTagger.invalidateFlowsByLogicalPort(id, portId))
            }
            // 1. Invalidate all arp requests
            // 2. Invalidate all flooded flows to the router port's specific MAC
            // We don't expect MAC migration in this case otherwise we'd be
            // invalidating Unicast flows to the device port's MAC
            val addedPortMap = newMacToLogicalPortId -- macToLogicalPortId.keys
            if (addedPortMap.size > 0)
                flowController !
                FlowController.InvalidateFlowsByTag(
                FlowTagger.invalidateArpRequests(id))
            for ((mac, portId) <- addedPortMap) {
                flowController !
                    FlowController.InvalidateFlowsByTag(
                        FlowTagger.invalidateFloodedFlowsByDstMac(id, mac,
                            Bridge.UNTAGGED_VLAN_ID))
            }
        }
        macToLogicalPortId = newMacToLogicalPortId
        ipToMac = newIpToMac
    }

    def build() {
        log.debug("Building the bridge for {}", id)
        // send messages to the BridgeManager
        // Convert the mutable map to immutable
        bridgeMgr ! BridgeManager.TriggerUpdate(cfg,
            collection.immutable.HashMap(vlanMacTableMap.toSeq: _*), ip4MacMap,
            collection.immutable.HashMap(macToLogicalPortId.toSeq: _*),
            collection.immutable.HashMap(ipToMac.toSeq: _*),
            vlanBridgePeerPortId, vlanPortMap)
    }

    private class MacTableNotifyCallBack(vlanId: JShort)
        extends Callback3[MAC, UUID, UUID] {
        final val log =
            LoggerFactory.getLogger(classOf[MacTableNotifyCallBack])

        def call(mac: MAC, oldPort: UUID, newPort: UUID) {
            log.debug("Mac-Port mapping for MAC {}, VLAN ID {} was updated" +
                " from {} to {}", Array(mac, vlanId, oldPort, newPort))

            //1. MAC, VLAN was removed from port
            if (newPort == null && oldPort != null) {
                log.debug("MAC {}, VLAN ID {} removed from port {}",
                    Array(mac, oldPort, vlanId))
                flowController ! FlowController.InvalidateFlowsByTag(
                    FlowTagger.invalidateFlowsByPort(id, mac, vlanId, oldPort))
            }
            //2. MAC, VLAN moved from port-x to port-y
            if (newPort != null && oldPort != null
                && !newPort.equals(oldPort)) {
                log.debug("MAC {}, VLAN ID {} moved from port {} to {}",
                          Array(mac, vlanId, oldPort, newPort))
                flowController ! FlowController.InvalidateFlowsByTag(
                    FlowTagger.invalidateFlowsByPort(id, mac, vlanId, oldPort))
            }
            //3. MAC was added -> invalidate flooded flows. Now we have the MAC
            // entry in the table so we can deliver it to the proper port instead
            // of flooding it.
            // As regards broadcast or arp requests:
            //   1. If this port was just added to the PortSet, the invalidation
            //    will occur in the VirtualToPhysicalMapper.
            //   2. If we just forgot the MAC port association, no need of invalidating,
            //    the port was in the PortSet, broadcast and ARP requests were
            //    correctly delivered.
            if (newPort != null && oldPort == null){
                log.debug("MAC {}, VLAN ID {} added to port {}",
                    Array(mac, vlanId, newPort))
                flowController ! FlowController.InvalidateFlowsByTag(
                    FlowTagger.invalidateFloodedFlowsByDstMac(id, mac, vlanId)
                )
            }
        }
    }

}

