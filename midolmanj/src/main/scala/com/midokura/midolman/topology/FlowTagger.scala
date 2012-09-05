/*
 * Copyright 2012 Midokura Pte. Ltd.
 */

package com.midokura.midolman.topology

import java.util.UUID
import com.midokura.packets.MAC

object FlowTagger {

    def invalidateAllDeviceFlowsTag(device: UUID): AnyRef =
        (device)
    
    def invalidateAllMACFlowsTag(bridgeId: UUID, mac: MAC): AnyRef =
        (bridgeId, mac)

    def invalidateAllMacPortFlows(bridgeId: UUID, mac: MAC, port: UUID): AnyRef =
        (bridgeId, mac, port)

    def invalidateBroadCastFlows(bridgeId: UUID): AnyRef =
        (bridgeId, "multicast")
}
