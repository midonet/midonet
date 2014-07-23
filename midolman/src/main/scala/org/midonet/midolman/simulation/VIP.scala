/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */

package org.midonet.midolman.simulation

import java.util.UUID

import org.midonet.packets.{TCP, IPv4Addr}

class VIP (val id: UUID, val adminStateUp: Boolean, val poolId: UUID,
           val address: IPv4Addr, val protocolPort: Int,
           val isStickySourceIP: Boolean) {

    def matches(pktContext: PacketContext) = {
        val pktMatch = pktContext.wcmatch

        adminStateUp && pktMatch.getNetworkDestinationIP == address &&
            pktMatch.getTransportDestination.toInt == protocolPort &&
            pktMatch.getNetworkProtocol == TCP.PROTOCOL_NUMBER
    }

    def matchesReturn(pktContext: PacketContext) = {
        val pktMatch = pktContext.wcmatch

        adminStateUp && pktMatch.getNetworkSourceIP == address &&
                pktMatch.getTransportSource.toInt == protocolPort &&
                pktMatch.getNetworkProtocol == TCP.PROTOCOL_NUMBER
    }
}
