/*
 * Copyright (c) 2014 Midokura Europe SARL, All Rights Reserved.
 */

package org.midonet.midolman.simulation

import java.util.UUID

import org.midonet.packets.IPv4Addr

class VIP (val id: UUID, val adminStateUp: Boolean, val poolId: UUID,
           val address: IPv4Addr, val protocolPort:Int,
           val sessionPersistence: String) {

    def matches(pktContext: PacketContext) = {
        val pktMatch = pktContext.origMatch

        adminStateUp && pktMatch.getNetworkDestinationIP == address &&
            pktMatch.getTransportDestinationObject.toInt == protocolPort
    }

}
