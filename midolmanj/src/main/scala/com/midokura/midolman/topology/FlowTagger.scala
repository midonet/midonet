/*
 * Copyright 2012 Midokura Pte. Ltd.
 */

package com.midokura.midolman.topology

import java.util.UUID
import com.midokura.packets.MAC


object
FlowTagger {

    def invalidateFlowsByDevice(device: UUID): AnyRef =
        (device)

    def invalidateFlowsByDeviceFilter(device: UUID, filter: UUID): AnyRef =
        device.toString + filter.toString

    def invalidateFloodedFlowsByMac(bridgeId: UUID, mac: MAC): AnyRef =
        (bridgeId.toString + mac.toString)

    def invalidateFlowsByPort(bridgeId: UUID, mac: MAC,
                                  port: UUID): AnyRef =
        (bridgeId.toString + mac.toString + port.toString)

    def invalidateBroadcastFlows(bridgeId: UUID, portSet: UUID): AnyRef =
        (bridgeId.toString + portSet.toString)

    def invalidateFlowsByLogicalPort(bridgeId: UUID, logicalPortId: UUID): AnyRef =
        (bridgeId.toString + logicalPortId.toString)

    def invalidateDPPort(port: Short): AnyRef = (port.toString)

    def invalidatePort(port: UUID): AnyRef = port.toString

    def invalidateByRoute(routerId: UUID, routeHashCode: Int): Any =
        (routerId.toString + routeHashCode.toString)

    def invalidateByIp(routerId: UUID, ipDestination: Int): Any =
        (routerId.toString + ipDestination.toString)
}
