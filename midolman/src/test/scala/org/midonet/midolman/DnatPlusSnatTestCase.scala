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
package org.midonet.midolman

import akka.testkit.TestProbe
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import org.midonet.cluster.data.{Router => ClusterRouter}
import org.midonet.cluster.data.host.Host
import org.midonet.midolman.DeduplicationActor.DiscardPacket
import org.midonet.midolman.layer3.Route.NextHop
import org.midonet.midolman.rules.{NatTarget, RuleResult, Condition}
import org.midonet.midolman.topology.LocalPortActive
import org.midonet.midolman.topology.VirtualToPhysicalMapper.HostRequest
import org.midonet.midolman.util.RouterHelper
import org.midonet.midolman.util.MidolmanTestCase
import org.midonet.midolman.util.guice.OutgoingMessage
import org.midonet.packets._
import org.midonet.util.Range

@Category(Array(classOf[SimulationTests]))
@RunWith(classOf[JUnitRunner])
class DnatPlusSnatTestCase extends MidolmanTestCase
        with RouterHelper {
    var router: ClusterRouter = null
    var host: Host = null
    var packetEventsProbe: TestProbe = null

    override def beforeTest() {
        packetEventsProbe = newProbe()
        actors.eventStream.subscribe(packetEventsProbe.ref, classOf[PacketsExecute])

        host = newHost("myself", hostId())
        host should not be null
        router = newRouter("router")
        router should not be null

        initializeDatapath() should not be (null)
        requestOfType[HostRequest](vtpProbe())
        requestOfType[OutgoingMessage](vtpProbe())

        val rtrPort1 = newRouterPort(router,
            MAC.fromString("02:aa:bb:bb:aa:11"), "10.0.0.1", "10.0.0.0", 24)
        rtrPort1 should not be null
        newRoute(router, "0.0.0.0", 0, "10.0.0.0", 24, NextHop.PORT,
            rtrPort1.getId, null, 10)
        materializePort(rtrPort1, host, "port1")
        requestOfType[LocalPortActive](portsProbe)

        val rtrPort2 = newRouterPort(router,
            MAC.fromString("02:aa:bb:bb:aa:21"), "10.0.1.1", "10.0.1.0", 24)
        rtrPort1 should not be null
        newRoute(router, "0.0.0.0", 0, "10.0.1.0", 24, NextHop.PORT,
            rtrPort2.getId, null, 10)
        materializePort(rtrPort2, host, "port2")
        requestOfType[LocalPortActive](portsProbe)

        flowProbe().expectMsgType[DatapathController.DatapathReady].
            datapath should not be (null)
        drainProbes()

        val inChain = newInboundChainOnRouter("InFilter", router)
        val outChain = newOutboundChainOnRouter("OutFilter", router)
        /* InChain's rules:
         *   1: dst 1.1.1.1:80 => DNAT to 10.0.1.2:81 or 10.0.1.3:81
         *   2: dst 10.0.1.1 => REV_SNAT
         *
         * OutChain's rules:
         *   1: src 10.0.1.2:81 or 10.0.1.3:81 => REV_DNAT  (need 2 rules)
         *   2: src in 10.0.0.0/24, SNAT to 10.0.1.1
         */
        var tcpCond = new Condition()
        tcpCond.nwProto = Byte.box(TCP.PROTOCOL_NUMBER)
        tcpCond.nwDstIp = new IPv4Subnet(IPv4Addr.fromString("1.1.1.1"), 32)
        tcpCond.tpDst = new Range(Integer.valueOf(80))
        var nat = new NatTarget(IPv4Addr("10.0.1.2").addr,
            IPv4Addr("10.0.1.3").addr, 81, 81)
        newForwardNatRuleOnChain(inChain, 1, tcpCond,
            RuleResult.Action.ACCEPT, Set(nat), true)
        tcpCond = new Condition()
        tcpCond.nwProto = Byte.box(TCP.PROTOCOL_NUMBER)
        tcpCond.nwDstIp = new IPv4Subnet(IPv4Addr.fromString("10.0.1.1"), 32)
        newReverseNatRuleOnChain(inChain, 1, tcpCond,
            RuleResult.Action.ACCEPT, false)
        tcpCond = new Condition()
        tcpCond.nwProto = Byte.box(TCP.PROTOCOL_NUMBER)
        tcpCond.nwSrcIp = new IPv4Subnet(IPv4Addr.fromString("10.0.1.2"), 32)
        tcpCond.tpSrc = new Range(Integer.valueOf(81))
        // Now the outbound chain.
        newReverseNatRuleOnChain(outChain, 1, tcpCond,
            RuleResult.Action.ACCEPT, true)
        tcpCond = new Condition()
        tcpCond.nwProto = Byte.box(TCP.PROTOCOL_NUMBER)
        tcpCond.nwSrcIp = new IPv4Subnet(IPv4Addr.fromString("10.0.1.3"), 32)
        tcpCond.tpSrc = new Range(Integer.valueOf(81))
        newReverseNatRuleOnChain(outChain, 1, tcpCond,
            RuleResult.Action.ACCEPT, true)
        tcpCond = new Condition()
        tcpCond.nwProto = Byte.box(TCP.PROTOCOL_NUMBER)
        tcpCond.nwSrcIp = new IPv4Subnet(IPv4Addr.fromString("10.0.0.0"), 24)
        nat = new NatTarget(IPv4Addr("10.0.1.1").addr,
            IPv4Addr("10.0.1.1").addr, 11000, 30000)
        newForwardNatRuleOnChain(outChain, 1, tcpCond,
            RuleResult.Action.ACCEPT, Set(nat), false)
        drainProbes()
    }

    def test() {
        val clientGw = IPv4Addr("10.0.0.1")
        val clientGwMac = MAC.fromString("02:aa:bb:bb:aa:11")
        val client1 = IPv4Addr("10.0.0.2")
        val client1Mac = MAC.fromString("02:aa:bb:bb:aa:12")
        val serverGw = IPv4Addr("10.0.1.1")
        val serverGwMac = MAC.fromString("02:aa:bb:bb:aa:21")
        val server1 = IPv4Addr("10.0.1.2")
        val server1Mac = MAC.fromString("02:aa:bb:bb:aa:22")
        val server2 = IPv4Addr("10.0.1.3")
        val server2Mac = MAC.fromString("02:aa:bb:bb:aa:23")

        val dnatDst = IPv4Addr("1.1.1.1")

        // Feed ARP cache for the client and 2 servers
        feedArpCache("port1", client1.addr, client1Mac,
            clientGw.addr, clientGwMac)
        requestOfType[DiscardPacket](discardPacketProbe)
        feedArpCache("port2", server1.addr, server1Mac,
            serverGw.addr, serverGwMac)
        requestOfType[DiscardPacket](discardPacketProbe)
        feedArpCache("port2", server2.addr, server2Mac,
            serverGw.addr, serverGwMac)
        requestOfType[DiscardPacket](discardPacketProbe)
        drainProbe(packetEventsProbe)

        // Send a forward packet that will be both DNATed and SNATed.
        injectTcp("port1", client1Mac, client1, 12345, clientGwMac,
            dnatDst, 80)
        var pktOut = requestOfType[PacketsExecute](packetEventsProbe)
        var outPorts = getOutPacketPorts(pktOut)
        outPorts should have size(1)
        outPorts should contain (5.toShort)
        var eth = applyOutPacketActions(pktOut)
        eth.getSourceMACAddress should be(serverGwMac)
        eth.getDestinationMACAddress should (
            be(server1Mac) or be(server2Mac))
        var ipPak = eth.getPayload.asInstanceOf[IPv4]
        ipPak should not be null
        ipPak.getSourceAddress should be (serverGw.addr)
        ipPak.getDestinationAddress should (
            be (server1.addr) or be (server2.addr))
        var tcpPak = ipPak.getPayload.asInstanceOf[TCP]
        val snatPort = tcpPak.getSourcePort
        snatPort should not be (12345)
        tcpPak.getDestinationPort should be (81)

        // Send a reply packet that will be reverse-SNATed and
        // reverse-DNATed.
        val fromServer1 = ipPak.getDestinationAddress == server1.addr
        val serverIp = if (fromServer1) server1 else server2
        val serverMac = if (fromServer1) server1Mac else server2Mac
        injectTcp("port2", serverMac, serverIp, 81,
            serverGwMac, serverGw, snatPort)
        pktOut = requestOfType[PacketsExecute](packetEventsProbe)
        outPorts = getOutPacketPorts(pktOut)
        outPorts should (have size(1) and contain (4.toShort))
        eth = applyOutPacketActions(pktOut)
        eth.getSourceMACAddress should be(clientGwMac)
        eth.getDestinationMACAddress should be(client1Mac)
        ipPak = eth.getPayload.asInstanceOf[IPv4]
        ipPak should not be null
        ipPak.getSourceAddress should be (dnatDst.addr)
        ipPak.getDestinationAddress should be(client1.addr)
        tcpPak = ipPak.getPayload.asInstanceOf[TCP]
        tcpPak.getSourcePort should be (80)
        tcpPak.getDestinationPort should be (12345)
    }
}
