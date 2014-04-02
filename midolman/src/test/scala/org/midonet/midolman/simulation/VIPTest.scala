/*
 * Copyright (c) 2014 Midokura Europe SARL, All Rights Reserved.
 */

package org.midonet.midolman.simulation

import java.util.UUID
import compat.Platform

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import org.midonet.cluster.data.l4lb
import org.midonet.midolman.MidolmanTestCase
import org.midonet.packets.{TCP, UDP, IPv4Addr}
import org.midonet.sdn.flows.WildcardMatch

@RunWith(classOf[JUnitRunner])
class VIPTest extends MidolmanTestCase {

    def testVipMatching() {
        import VIPTest.createVip

        val addr1 = IPv4Addr.fromString("10.0.0.1")
        val port1 = 22

        val addr2 = IPv4Addr.fromString("10.0.0.2")
        val port2 = 44

        val tcpIngressMatch = new WildcardMatch()
                .setNetworkDestination(addr1)
                .setTransportDestination(port1)
                .setNetworkProtocol(TCP.PROTOCOL_NUMBER)

        val tcpContext = new PacketContext(None, null,
                                        Platform.currentTime + 10000, null,
                                        null, null, true,
                                        None, tcpIngressMatch)(actors())

        val udpIngressMatch = tcpIngressMatch.clone().setNetworkProtocol(UDP.PROTOCOL_NUMBER)
        val udpContext = new PacketContext(None, null,
            Platform.currentTime + 10000, null,
            null, null, true, None, udpIngressMatch)(actors())

        // Admin state up VIP with same addr / port should match
        val vip1Up = createVip(true, addr1, port1)
        assert(vip1Up.matches(tcpContext))

        // Admin state up VIP with same addr / port should not match UDP context
        assert(!vip1Up.matches(udpContext))

        // Admin state down VIP with same addr / port should not match
        val vip1Down = createVip(false, addr1, port1)
        assert(!vip1Down.matches(tcpContext))

        // Admin state up VIP with diff addr / port should not match
        val vip2Up = createVip(true, addr2, port2)
        assert(!vip2Up.matches(tcpContext))

        // Admin state down VIP with diff addr / port should not match
        val vip2Down = createVip(false, addr2, port2)
        assert(!vip2Down.matches(tcpContext))
    }
}

object VIPTest {
    def createVip(adminStateUp: Boolean, address: IPv4Addr,
                  protocolPort: Int): VIP = {
        val vipId = UUID.randomUUID()
        val poolId = UUID.randomUUID()

        new VIP(vipId, adminStateUp, poolId,address,
            protocolPort, isStickySourceIP = true,
            l4lb.VIP.VIP_STICKY_TIMEOUT_SECONDS)
    }
}