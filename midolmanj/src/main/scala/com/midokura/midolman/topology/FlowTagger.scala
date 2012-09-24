/*
 * Copyright 2012 Midokura Pte. Ltd.
 */

package com.midokura.midolman.topology

import java.util.UUID
import com.midokura.packets.MAC


object FlowTagger {

    def invalidateFlowsByDevice(device: UUID): AnyRef =
        (device)

    def invalidateFlowsByMac(bridgeId: UUID, mac: MAC): AnyRef =
        (bridgeId, mac)

    def invalidateFlowsByPort(bridgeId: UUID, mac: MAC,
                                  port: UUID): AnyRef =
        (bridgeId, mac, port)

    def invalidateBroadcastFlows(bridgeId: UUID): AnyRef =
        (bridgeId, "broadcast")
}
