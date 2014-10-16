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

package org.midonet.midolman.simulation

import java.util.UUID

import org.midonet.packets.{TCP, IPv4Addr}

class VIP (val id: UUID, val adminStateUp: Boolean, val poolId: UUID,
           val address: IPv4Addr, val protocolPort: Int,
           val isStickySourceIP: Boolean) {

    def matches(pktContext: PacketContext) = {
        val pktMatch = pktContext.wcmatch

        adminStateUp && pktMatch.getNetworkDestinationIP == address &&
            pktMatch.getDstPort.toInt == protocolPort &&
            pktMatch.getNetworkProto == TCP.PROTOCOL_NUMBER
    }

    def matchesReturn(pktContext: PacketContext) = {
        val pktMatch = pktContext.wcmatch

        adminStateUp && pktMatch.getNetworkSourceIP == address &&
                pktMatch.getSrcPort.toInt == protocolPort &&
                pktMatch.getNetworkProto == TCP.PROTOCOL_NUMBER
    }
}
