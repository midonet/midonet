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

import java.util.UUID

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import org.midonet.cluster.data.ports.RouterPort
import org.midonet.midolman.PacketWorkflow.AddVirtualWildcardFlow
import org.midonet.midolman.layer3.Route._
import org.midonet.midolman.rules.{RuleResult, NatTarget, Condition}
import org.midonet.midolman.simulation.{Router => SimRouter}
import org.midonet.midolman.state.NatState.{FWD_SNAT, FWD_DNAT, NatBinding, NatKey}
import org.midonet.midolman.topology.VirtualTopologyActor
import org.midonet.midolman.util.MidolmanSpec
import org.midonet.odp.flows._
import org.midonet.packets._
import org.midonet.packets.util.PacketBuilder._
import org.midonet.sdn.flows.VirtualActions.FlowActionOutputToVrnPort
import org.midonet.sdn.state.{ShardedFlowStateTable, FlowStateTransaction}
import org.midonet.util.Range

@RunWith(classOf[JUnitRunner])
class DnatPlusSnatTest extends MidolmanSpec {

    registerActors(VirtualTopologyActor -> (() => new VirtualTopologyActor))

    var router: UUID = _
    var port1: RouterPort = _
    var port2: RouterPort = _

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

    val dst = IPv4Addr("1.1.1.1")

    override def beforeTest(): Unit = {
        router = newRouter("router")

        port1 = newRouterPort(router, clientGwMac,
                                  clientGw.toString, "10.0.0.0", 24)
        newRoute(router, "0.0.0.0", 0, "10.0.0.0", 24, NextHop.PORT,
                 port1.getId, null, 10)
        materializePort(port1, hostId, "port1")

        port2 = newRouterPort(router, serverGwMac,
                                  serverGw.toString, "10.0.1.0", 24)
        newRoute(router, "0.0.0.0", 0, "10.0.1.0", 24, NextHop.PORT,
                 port2.getId, null, 10)
        materializePort(port2, hostId, "port2")

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
        tcpCond.nwProto = TCP.PROTOCOL_NUMBER
        tcpCond.nwDstIp = new IPv4Subnet(dst, 32)
        tcpCond.tpDst = new Range(80)
        val dnat = new NatTarget(server1.addr, server2.addr, 81, 81)
        newForwardNatRuleOnChain(inChain, 1, tcpCond, RuleResult.Action.ACCEPT,
                                 Set(dnat), isDnat = true)
        tcpCond = new Condition()
        tcpCond.nwProto = TCP.PROTOCOL_NUMBER
        tcpCond.nwDstIp = new IPv4Subnet(serverGw, 32)
        newReverseNatRuleOnChain(inChain, 1, tcpCond, RuleResult.Action.ACCEPT,
                                 isDnat = false)
        tcpCond = new Condition()
        tcpCond.nwProto = TCP.PROTOCOL_NUMBER
        tcpCond.nwSrcIp = new IPv4Subnet(server1, 32)
        tcpCond.tpSrc = new Range(81)
        // Now the outbound chain.
        newReverseNatRuleOnChain(outChain, 1, tcpCond, RuleResult.Action.ACCEPT,
                                 isDnat = true)
        tcpCond = new Condition()
        tcpCond.nwProto = TCP.PROTOCOL_NUMBER
        tcpCond.nwSrcIp = new IPv4Subnet(server2, 32)
        tcpCond.tpSrc = new Range(81)
        newReverseNatRuleOnChain(outChain, 1, tcpCond,
                                 RuleResult.Action.ACCEPT, isDnat = true)
        tcpCond = new Condition()
        tcpCond.nwProto = TCP.PROTOCOL_NUMBER
        tcpCond.nwSrcIp = new IPv4Subnet(IPv4Addr.fromString("10.0.0.0"), 24)
        val snat = new NatTarget(serverGw.addr, serverGw.addr, 11000, 30000)
        newForwardNatRuleOnChain(outChain, 1, tcpCond, RuleResult.Action.ACCEPT,
                                 Set(snat), isDnat = false)
    }

    def simRouter: SimRouter = fetchDevice[SimRouter](router)

    scenario("DNAT and SNAT rules coexist") {
        feedArpTable(simRouter, client1, client1Mac)
        feedArpTable(simRouter, server1, server1Mac)
        feedArpTable(simRouter, server2, server2Mac)

        val stateTable = new ShardedFlowStateTable[NatKey, NatBinding]().addShard()
        implicit val natTx = new FlowStateTransaction(stateTable)

        // Send a forward packet that will be both DNATed and SNATed
        val pkt = { eth src client1Mac dst clientGwMac } <<
                  { ip4 src client1 dst dst} <<
                  { tcp src 12345 dst 80 }

        val (simRes, pktCtx) = simulate(packetContextFor(pkt, port1.getId))
        simRes should be (AddVirtualWildcardFlow)

        pktCtx.virtualFlowActions should have size 4
        var ethKey = setKey[FlowKeyEthernet](pktCtx.virtualFlowActions.get(0))
        ethKey.eth_src should be (serverGwMac.getAddress)
        ethKey.eth_dst should (be(server1Mac.getAddress) or
                               be(server2Mac.getAddress))

        var ipKey = setKey[FlowKeyIPv4](pktCtx.virtualFlowActions.get(1))
        ipKey.ipv4_src should be (serverGw.toInt)
        ipKey.ipv4_dst should (be(server1.toInt) or be(server2.toInt))

        var tcpKey = setKey[FlowKeyTCP](pktCtx.virtualFlowActions.get(2))
        tcpKey.tcp_src should not be 12345
        tcpKey.tcp_dst should be (81)

        pktCtx.virtualFlowActions.get(3) should be (FlowActionOutputToVrnPort(port2.getId))

        val dnatKey = NatKey(FWD_DNAT, client1, 12345, dst, 80,
                             TCP.PROTOCOL_NUMBER, router)
        val dnatBinding = stateTable.get(dnatKey)
        dnatBinding.networkAddress should (be(server1) or be(server2))
        dnatBinding.transportPort should be (81)

        val revDnatKey = dnatKey.returnKey(dnatBinding)
        val revDnatBinding = stateTable.get(revDnatKey)
        revDnatBinding.networkAddress should be (dst)
        revDnatBinding.transportPort should be (80)

        var snatKey = NatKey(FWD_SNAT, client1, 12345, server1, 81,
                             TCP.PROTOCOL_NUMBER, router)
        if (stateTable.get(snatKey) == null) {
            snatKey = NatKey(FWD_SNAT, client1, 12345, server2, 81,
                             TCP.PROTOCOL_NUMBER, router)
        }
        val snatBinding = stateTable.get(snatKey)
        snatBinding.networkAddress should be (serverGw)
        snatBinding.transportPort should be (tcpKey.tcp_src)

        val revSnatKey = snatKey.returnKey(snatBinding)
        val revSnatBinding = stateTable.get(revSnatKey)
        revSnatBinding.networkAddress should be (client1)
        revSnatBinding.transportPort should be (12345)

        // Send a reply packet that will be reverse-SNATed and
        // reverse-DNATed.
        val serverIp = ipKey.ipv4_dst
        val serverMac = MAC.fromAddress(ethKey.eth_dst)
        val returnPkt = { eth src serverMac dst serverGwMac } <<
                        { ip4 src serverIp dst serverGw } <<
                        { tcp src 81 dst tcpKey.tcp_src.toShort }

        val (returnSimRes, returnPktCtx) = simulate(packetContextFor(returnPkt, port2.getId))
        returnSimRes should be (AddVirtualWildcardFlow)

        returnPktCtx.virtualFlowActions should have size 4
        ethKey = setKey[FlowKeyEthernet](returnPktCtx.virtualFlowActions.get(0))
        ethKey.eth_src should be (clientGwMac.getAddress)
        ethKey.eth_dst should be (client1Mac.getAddress)

        ipKey = setKey[FlowKeyIPv4](returnPktCtx.virtualFlowActions.get(1))
        ipKey.ipv4_src should be (dst.toInt)
        ipKey.ipv4_dst should be (client1.toInt)

        tcpKey = setKey[FlowKeyTCP](returnPktCtx.virtualFlowActions.get(2))
        tcpKey.tcp_src should be (80)
        tcpKey.tcp_dst should be (12345)

        returnPktCtx.virtualFlowActions.get(3) should be (FlowActionOutputToVrnPort(port1.getId))
    }

    private def setKey[T <: FlowKey](action: FlowAction) =
        action.asInstanceOf[FlowActionSetKey].getFlowKey.asInstanceOf[T]
}
