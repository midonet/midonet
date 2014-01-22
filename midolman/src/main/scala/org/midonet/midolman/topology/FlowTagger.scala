/*
 * Copyright 2012 Midokura Pte. Ltd.
 */

package org.midonet.midolman.topology

import java.util.UUID
import java.lang.{Short => JShort}
import org.midonet.packets.{IPAddr, MAC}
import org.midonet.util.collections.WeakObjectPool


object FlowTagger {
    val INSTANCE_POOL : WeakObjectPool[String] = new WeakObjectPool[String]()

    /**
     * Invalidate flows that were created after traversing through a device
     */
    def invalidateFlowsByDevice(device: UUID): AnyRef = {
        val tag = "device:" + device.toString
        INSTANCE_POOL.sharedRef(tag)
    }

    def invalidateFlowsByDeviceFilter(device: UUID, filter: UUID): AnyRef = {
        val tag = "filter:" + device.toString + ":" + filter.toString
        INSTANCE_POOL.sharedRef(tag)
    }

    /**
     * Used to invalidate all the flows on "vlanId" addressed to the unknown
     * "mac", which were thus flooded to the bridge's portset.
     */
    def invalidateFloodedFlowsByDstMac(bridgeId: UUID, mac: MAC,
                                       vlanId: JShort): AnyRef = {
        val tag = "br_flood_mac:" + bridgeId.toString + ":" + mac.toString +
            ":" + vlanId.toString
        INSTANCE_POOL.sharedRef(tag)
    }

    def invalidateArpRequests(bridgeId: UUID): AnyRef = {
        val tag = "br_arp_req:" + bridgeId.toString
        INSTANCE_POOL.sharedRef(tag)
    }

    /**
     * Used to invalidate all the flows on "vlan" addressed to "mac" that were
     * sent to "port" in the given bridge.
     */
    def invalidateFlowsByPort(bridgeId: UUID, mac: MAC, vlanId: JShort,
                              port: UUID): AnyRef = {
        val tag = "br_fwd_mac:" + bridgeId.toString + ":" + mac.toString + ":" +
            vlanId.toString + ":" + port.toString
        INSTANCE_POOL.sharedRef(tag)
    }

    def invalidateBroadcastFlows(bridgeId: UUID, portSet: UUID): AnyRef = {
        val tag = "br_flood:" + bridgeId.toString + ":" + portSet.toString
        INSTANCE_POOL.sharedRef(tag)
    }

    def invalidateFlowsByLogicalPort(bridgeId: UUID,
                                     logicalPortId: UUID): AnyRef = {
        val tag = "br_fwd_lport:" + bridgeId.toString + ":" + logicalPortId.toString
        INSTANCE_POOL.sharedRef(tag)
    }

    def invalidateDPPort(port: Short): AnyRef = {
        val tag = "dp_port:" + port.toString
        INSTANCE_POOL.sharedRef(tag)
    }

    def invalidateTunnelPort(route: (Int,Int)): AnyRef =
        INSTANCE_POOL.sharedRef("tunnel: " + route)

    def invalidateByTunnelKey(key: Long): AnyRef = {
        val tag = "tun_key:" + key.toString
        INSTANCE_POOL.sharedRef(tag)
    }

    def invalidateByRoute(routerId: UUID, routeHashCode: Int): Any = {
        val tag = "rtr_route:" + routerId.toString + ":" + routeHashCode.toString
        INSTANCE_POOL.sharedRef(tag)
    }

    def invalidateByIp(routerId: UUID, ipDestination: IPAddr): Any = {
        val tag = "rtr_ip:" + routerId.toString + ":" + ipDestination.toString
        INSTANCE_POOL.sharedRef(tag)

    }

    def invalidateByBgp(bgpId: UUID): Any = {
        val tag = "bgp:" + bgpId
        INSTANCE_POOL.sharedRef(tag)
    }
}
