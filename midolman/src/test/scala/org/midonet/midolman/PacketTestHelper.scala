/*
 * Copyright 2015 Midokura SARL
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

package org.midonet.midolman

import org.midonet.odp.{FlowMatches, Packet}
import org.midonet.packets.Ethernet
import org.midonet.packets.util.EthBuilder
import org.midonet.packets.util.PacketBuilder._

/**
 * PacketTestHelper makes the dummy packets for tests.
 */
trait PacketTestHelper {
    protected def makeFrame(tpDst: Short, tpSrc: Short = 10101) =
        { eth addr "00:02:03:04:05:06" -> "00:20:30:40:50:60" } <<
            { ip4 addr "192.168.0.1" --> "192.168.0.2" } <<
            { udp ports tpSrc ---> tpDst }

    implicit def ethBuilder2Packet(ethBuilder: EthBuilder[Ethernet]): Packet = {
        val frame: Ethernet = ethBuilder
        new Packet(frame, FlowMatches.fromEthernetPacket(frame))
            .setReason(Packet.Reason.FlowTableMiss)
    }

    def makePacket(variation: Short): Packet = makeFrame(variation)
}
