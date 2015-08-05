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

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import org.midonet.midolman.state.l4lb.VipSessionPersistence
import org.midonet.midolman.util.MidolmanSpec
import org.midonet.odp.FlowMatch
import org.midonet.packets.{IPv4Addr, TCP, UDP}

@RunWith(classOf[JUnitRunner])
class VipTest extends MidolmanSpec {

    scenario ("Matching VIPs") {
        val addr1 = IPv4Addr.fromString("10.0.0.1")
        val port1 = 22

        val addr2 = IPv4Addr.fromString("10.0.0.2")
        val port2 = 44

        val tcpIngressMatch = new FlowMatch()
                .setNetworkDst(addr1)
                .setDstPort(port1)
                .setNetworkProto(TCP.PROTOCOL_NUMBER)

        val tcpContext = new PacketContext(1, null, tcpIngressMatch)

        val udpIngressMatch = tcpIngressMatch.clone().setNetworkProto(UDP.PROTOCOL_NUMBER)
        val udpContext = new PacketContext(1, null, udpIngressMatch)

        // Admin state up VIP with same addr / port should match
        val vip1Up = createTestVip(true, addr1, port1)
        assert(vip1Up.matches(tcpContext))

        // Admin state up VIP with same addr / port should not match UDP context
        assert(!vip1Up.matches(udpContext))

        // Admin state down VIP with same addr / port should not match
        val vip1Down = createTestVip(false, addr1, port1)
        assert(!vip1Down.matches(tcpContext))

        // Admin state up VIP with diff addr / port should not match
        val vip2Up = createTestVip(true, addr2, port2)
        assert(!vip2Up.matches(tcpContext))

        // Admin state down VIP with diff addr / port should not match
        val vip2Down = createTestVip(false, addr2, port2)
        assert(!vip2Down.matches(tcpContext))
    }

    def createTestVip(adminStateUp: Boolean, address: IPv4Addr,
                      protocolPort: Int): Vip = {
        val vipId = UUID.randomUUID()
        val poolId = UUID.randomUUID()
        val loadBalancerId = UUID.randomUUID()

        new Vip(vipId, adminStateUp, poolId,address, protocolPort,
                VipSessionPersistence.SOURCE_IP)
    }
}
