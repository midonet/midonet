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

import java.nio.ByteBuffer
import java.util.{HashSet, UUID}

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import org.midonet.midolman.PacketWorkflow.HandlePackets
import org.midonet.midolman.layer3.Route
import org.midonet.midolman.layer3.Route.NextHop
import org.midonet.midolman.rules.{Condition, NatTarget, RuleResult}
import org.midonet.midolman.simulation.Router
import org.midonet.midolman.state.NatState.NatKey
import org.midonet.midolman.util.MidolmanSpec
import org.midonet.odp.flows.{FlowActions, FlowKeys}
import org.midonet.odp.{FlowMatch, Packet}
import org.midonet.packets.NatState.NatBinding
import org.midonet.packets.util.AddressConversions._
import org.midonet.packets.util.PacketBuilder._
import org.midonet.packets.{IPv4Addr, IPv4Subnet, MAC, _}
import org.midonet.sdn.state.ShardedFlowStateTable

@RunWith(classOf[JUnitRunner])
class IcmpErrorNatTest extends MidolmanSpec {

    private var nearRouter: UUID = _
    private var farRouter: UUID = _

    private val nearNwAddr = new IPv4Subnet("172.19.0.0", 24)

    private var nearLeftPort: UUID = _
    private val nearLeftPortAddr = new IPv4Subnet("172.19.0.2", 24)
    private val nearLeftPortMac: MAC = "02:0a:08:06:04:01"

    private var nearRightPort: UUID = _
    private val nearRightPortAddr = new IPv4Subnet("172.20.0.2", 32)
    private val nearRightPortMac: MAC = "02:0a:08:06:04:02"

    private var farPort: UUID = _
    private val farPortAddr = new IPv4Subnet("172.20.0.3", 32)
    private val farPortMac: MAC = "02:0a:08:06:05:01"

    private val srcMac = MAC.random()
    private val srcIp = new IPv4Subnet("172.19.0.4", 32)
    private val privateSrcIp = new IPv4Subnet("42.159.207.7", 32)

    private val dstIp = new IPv4Subnet("1.1.1.1", 32)
    private val privateDstIp = new IPv4Subnet("2.2.2.2", 32)

    private val pingId: Short = 8756

    override def beforeTest(): Unit = {
        nearRouter = newRouter("near_router")
        farRouter = newRouter("far_router")

        nearLeftPort = newRouterPort(nearRouter, nearLeftPortMac,
                                     nearLeftPortAddr)
        nearRightPort = newRouterPort(nearRouter, nearRightPortMac,
                                      nearRightPortAddr)
        farPort = newRouterPort(farRouter, farPortMac, farPortAddr)

        materializePort(nearLeftPort, hostId, "uplink")

        linkPorts(nearRightPort, farPort)

        newRoute(nearRouter, "0.0.0.0", 0,
                 privateDstIp.toNetworkAddress, privateDstIp.getPrefixLen,
                 NextHop.PORT, nearRightPort,
                 new IPv4Addr(Route.NO_GATEWAY).toString, 10)

        newRoute(nearRouter, "0.0.0.0", 0,
                 nearNwAddr.toNetworkAddress, nearNwAddr.getPrefixLen,
                 NextHop.PORT, nearLeftPort,
                 new IPv4Addr(Route.NO_GATEWAY).toString, 10)

        feedArpTable(fetchDevice[Router](nearRouter),
                     srcIp.toNetworkAddress, srcMac)

        fetchRouters(nearRouter, farRouter)
        fetchPorts(nearLeftPort, nearRightPort, farPort)

    }

    def setupDynamicNat(): Unit = {
        val rtrOutChain = newOutboundChainOnRouter("rtrOutChain", nearRouter)
        val rtrInChain = newInboundChainOnRouter("rtrInChain", nearRouter)

        val revSnatCond = new Condition()
        revSnatCond.inPortIds = new HashSet()
        revSnatCond.inPortIds.add(nearRightPort)
        revSnatCond.nwDstIp = privateSrcIp

        newReverseNatRuleOnChain(
            rtrInChain,
            1,
            revSnatCond,
            RuleResult.Action.ACCEPT,
            isDnat = false)

        val dnatCond = new Condition()
        dnatCond.inPortIds = new HashSet()
        dnatCond.inPortIds.add(nearLeftPort)
        dnatCond.nwDstIp = dstIp

        val dnatTarget = new NatTarget(privateDstIp.getAddress,
                                       privateDstIp.getAddress, 20, 20)
        newForwardNatRuleOnChain(
            rtrInChain,
            2,
            dnatCond,
            RuleResult.Action.ACCEPT,
            Set(dnatTarget),
            isDnat = true)

        val snatCond = new Condition()
        snatCond.nwSrcIp = srcIp

        val snatTarget = new NatTarget(privateSrcIp.getAddress,
                                       privateSrcIp.getAddress, 10, 10)
        newForwardNatRuleOnChain(
            rtrOutChain,
            1,
            snatCond,
            RuleResult.Action.ACCEPT,
            Set(snatTarget),
            isDnat = false)

        val revDnatCond = new Condition()
        revDnatCond.nwDstIp = srcIp

        newReverseNatRuleOnChain(
            rtrOutChain,
            2,
            revDnatCond,
            RuleResult.Action.ACCEPT,
            isDnat = true)

        fetchChains(rtrInChain, rtrOutChain)
    }

    def setupStaticNat(): Unit = {
        val rtrOutChain = newOutboundChainOnRouter("rtrOutChain", nearRouter)
        val rtrInChain = newInboundChainOnRouter("rtrInChain", nearRouter)

        // 1.1.1.1 -> 2.2.2.2 and vice versa
        val dnatCond1 = new Condition()
        dnatCond1.inPortIds = new HashSet()
        dnatCond1.inPortIds.add(nearLeftPort)
        dnatCond1.nwDstIp = dstIp

        val dnatTarget1 = new NatTarget(privateDstIp.getAddress,
                                        privateDstIp.getAddress, 0, 0)
        newForwardNatRuleOnChain(
            rtrInChain,
            1,
            dnatCond1,
            RuleResult.Action.ACCEPT,
            Set(dnatTarget1),
            isDnat = true)

        val revIcmpDnatCond1 = new Condition()
        revIcmpDnatCond1.outPortIds = new HashSet()
        revIcmpDnatCond1.outPortIds.add(nearLeftPort)
        revIcmpDnatCond1.icmpDataDstIp = privateDstIp

        val revIcmpDnatTarget1 = new NatTarget(dstIp.getAddress,
                                               dstIp.getAddress, 0, 0)
        newForwardNatRuleOnChain(
            rtrOutChain,
            1,
            revIcmpDnatCond1,
            RuleResult.Action.ACCEPT,
            Set(revIcmpDnatTarget1),
            isDnat = true)

        val snatCond1 = new Condition()
        snatCond1.outPortIds = new HashSet()
        snatCond1.outPortIds.add(nearLeftPort)
        snatCond1.nwSrcIp = privateDstIp // wont be true for icmp error

        val snatTarget1 = new NatTarget(dstIp.getAddress,
                                        dstIp.getAddress, 0, 0)
        newForwardNatRuleOnChain(
            rtrOutChain,
            1,
            snatCond1,
            RuleResult.Action.ACCEPT,
            Set(snatTarget1),
            isDnat = false)

        // 172.19.0.4 -> 42.159.207.7 and vice versa
        val snatCond2 = new Condition()
        snatCond2.inPortIds = new HashSet()
        snatCond2.inPortIds.add(nearLeftPort)
        snatCond2.nwSrcIp = srcIp

        val snatTarget2 = new NatTarget(privateSrcIp.getAddress,
                                        privateSrcIp.getAddress, 0, 0)
        newForwardNatRuleOnChain(
            rtrOutChain,
            2,
            snatCond2,
            RuleResult.Action.ACCEPT,
            Set(snatTarget2),
            isDnat = false)

        val dnatCond2 = new Condition()
        dnatCond2.inPortIds = new HashSet()
        dnatCond2.inPortIds.add(nearRightPort)
        dnatCond2.nwDstIp = privateSrcIp

        val dnatTarget2 = new NatTarget(srcIp.getAddress,
                                        srcIp.getAddress, 0, 0)
        newForwardNatRuleOnChain(
            rtrInChain,
            2,
            dnatCond2,
            RuleResult.Action.ACCEPT,
            Set(dnatTarget2),
            isDnat = true)

        fetchChains(rtrInChain, rtrOutChain)
    }

    val tcpReq: Ethernet =
        { eth src srcMac dst nearLeftPortMac } <<
        { ip4 src srcIp.toNetworkAddress dst dstIp.toNetworkAddress } <<
        { tcp src 1234 dst 6789 } <<
        { payload apply Array[Byte](1, 2) }

     val pingReq: Ethernet =
        { eth src srcMac dst nearLeftPortMac } <<
        { ip4 src srcIp.toNetworkAddress dst dstIp.toNetworkAddress } <<
        { icmp.echo id pingId }

    def testTCP(): Unit = {
        val table = new ShardedFlowStateTable[NatKey, NatBinding]().addShard()

        val fmatch = new FlowMatch(FlowKeys.fromEthernetPacket(tcpReq))
        fmatch.setInputPortNumber(1)
        val packet = new Packet(tcpReq, fmatch)

        val workflow = packetWorkflow(
            dpPortToVport = Map(1 -> nearLeftPort),
            natTable = table)

        var passed = false
        mockDpChannel.packetsExecuteSubscribe { (icmpError, actions) =>
            // The original icmp error
            matchIcmp(
                icmpError.getEthernet,
                farPortMac,
                nearRightPortMac,
                farPortAddr.toNetworkAddress,
                privateSrcIp.toNetworkAddress,
                ICMP.TYPE_UNREACH)

            val ip = ByteBuffer.wrap(
                icmpError.getEthernet
                    .getPayload.asInstanceOf[IPv4]
                    .getPayload.asInstanceOf[ICMP].getData)
            val wrapped = new IPv4()
            wrapped.deserialize(ip)
            wrapped.getTotalLength should be (42)
            matchIpv4(wrapped, srcIp.toNetworkAddress, dstIp.toNetworkAddress)
            val tcp = wrapped.getPayload.asInstanceOf[TCP]
            tcp.getSourcePort should be (1234)
            tcp.getDestinationPort should be (6789)

            // The modifications to the original icmp error
            actions should contain theSameElementsAs List(
                FlowActions.setKey(FlowKeys.ethernet(nearLeftPortMac, srcMac)),
                FlowActions.setKey(FlowKeys.ipv4(farPortAddr.getIntAddress(),
                                                 srcIp.getIntAddress(), 1.toByte,
                                                 0.toByte, 63.toByte, 0.toByte)),
                FlowActions.output(1))
            passed = true
        }

        workflow.handlePackets(packet)
        mockDpChannel.packetsSent should have size 1
        passed should be (true)
    }

    def testICMP(): Unit = {
        val table = new ShardedFlowStateTable[NatKey, NatBinding]().addShard()

        val fmatch = new FlowMatch(FlowKeys.fromEthernetPacket(pingReq))
        fmatch.setInputPortNumber(1)
        val packet = new Packet(pingReq, fmatch)

        val workflow = packetWorkflow(
            dpPortToVport = Map(1 -> nearLeftPort),
            natTable = table)

        var passed = false
        mockDpChannel.packetsExecuteSubscribe { (pingReply, actions) =>
            // The original icmp error
            matchIcmp(
                pingReply.getEthernet,
                farPortMac,
                nearRightPortMac,
                farPortAddr.toNetworkAddress,
                privateSrcIp.toNetworkAddress,
                ICMP.TYPE_UNREACH)

            val ip = ByteBuffer.wrap(
                pingReply.getEthernet
                    .getPayload.asInstanceOf[IPv4]
                    .getPayload.asInstanceOf[ICMP].getData)
            val wrapped = new IPv4()
            wrapped.deserialize(ip)
            matchIpv4(wrapped, srcIp.toNetworkAddress, dstIp.toNetworkAddress)
            val icmp = wrapped.getPayload.asInstanceOf[ICMP]
            icmp.getIdentifier should be (pingId)

            // The modifications to the original icmp error
            actions should contain theSameElementsAs List(
                FlowActions.setKey(FlowKeys.ethernet(nearLeftPortMac, srcMac)),
                FlowActions.setKey(FlowKeys.ipv4(farPortAddr.getIntAddress(),
                                                 srcIp.getIntAddress(), 1.toByte,
                                                 0.toByte, 63.toByte, 0.toByte)),
                FlowActions.output(1))
            passed = true
        }

        workflow.handlePackets(packet)
        mockDpChannel.packetsSent should have size 1
        passed should be (true)
    }

    feature ("ICMP errors are reverse NATed (with dynamic NAT)") {
        scenario ("For TCP/UDP packets") {
            setupDynamicNat()
            testTCP()
        }

        scenario ("For ICMP packets") {
            setupDynamicNat()
            testICMP()
        }
    }

    feature ("ICMP errors are reverse NATed (with static NAT)") {
        scenario ("For TCP/UDP packets") {
            setupStaticNat()
            testTCP()
        }

        scenario ("For ICMP packets") {
            setupStaticNat()
            testICMP()
        }
    }

    private def matchIcmp(
            eth: Ethernet,
            fromMac: MAC,
            toMac: MAC,
            fromIp: IPv4Addr,
            toIp: IPv4Addr,
            `type`: Byte): Unit = {
        matchEth(eth, fromMac, toMac)
        val ip = eth.getPayload().asInstanceOf[IPv4]
        matchIpv4(ip, fromIp, toIp)
        val icmp = ip.getPayload.asInstanceOf[ICMP]
        icmp.getType should be (`type`)
    }

    private def matchEth(
            eth: Ethernet,
            fromMac: MAC,
            toMac: MAC): Unit = {
        eth.getSourceMACAddress should be (fromMac)
        eth.getDestinationMACAddress should be (toMac)
    }

    private def matchIpv4(
            ip: IPv4,
            fromIp: IPv4Addr,
            toIp: IPv4Addr): Unit = {
        ip.getSourceIPAddress should be (fromIp)
        ip.getDestinationIPAddress should be (toIp)
    }
}
