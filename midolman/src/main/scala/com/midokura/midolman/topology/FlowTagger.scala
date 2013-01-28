/*
 * Copyright 2012 Midokura Pte. Ltd.
 */

package com.midokura.midolman.topology

import java.util.UUID
import com.midokura.packets.MAC


object
FlowTagger {

    def invalidateFlowsByDevice(device: UUID): AnyRef =
        "device:" + device.toString

    def invalidateFlowsByDeviceFilter(device: UUID, filter: UUID): AnyRef =
        "filter:" + device.toString + ":" + filter.toString

    def invalidateFloodedFlowsByDstMac(bridgeId: UUID, mac: MAC): AnyRef =
        "br_flood_mac:" + bridgeId.toString + ":" + mac.toString

    def invalidateArpRequests(bridgeId: UUID): AnyRef =
        "br_arp_req:" + bridgeId.toString

    def invalidateFlowsByPort(bridgeId: UUID, mac: MAC, port: UUID): AnyRef =
        "br_fwd_mac:" + bridgeId.toString + ":" + mac.toString + ":" +
            port.toString

    def invalidateBroadcastFlows(bridgeId: UUID, portSet: UUID): AnyRef =
        "br_flood:" + bridgeId.toString + ":" + portSet.toString

    def invalidateFlowsByLogicalPort(bridgeId: UUID,
                                     logicalPortId: UUID): AnyRef =
        "br_fwd_lport:" + bridgeId.toString + ":" + logicalPortId.toString

    def invalidateDPPort(port: Short): AnyRef =
        "dp_port:" + port.toString

    def invalidateByTunnelKey(key: Long): AnyRef =
        "tun_key:" + key.toString

    def invalidateByRoute(routerId: UUID, routeHashCode: Int): Any =
        "rtr_route:" + routerId.toString + ":" + routeHashCode.toString

    def invalidateByIp(routerId: UUID, ipDestination: Int): Any =
        "rtr_ip:" + routerId.toString + ":" + ipDestination.toString
}
