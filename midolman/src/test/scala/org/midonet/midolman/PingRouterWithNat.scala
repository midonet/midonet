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

import java.util.{HashSet,UUID}

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import org.midonet.midolman.layer3.Route
import org.midonet.midolman.layer3.Route.NextHop
import org.midonet.midolman.PacketWorkflow.HandlePackets
import org.midonet.midolman.rules.{NatTarget, RuleResult, Condition}
import org.midonet.midolman.simulation.Router
import org.midonet.midolman.state.NatState.{NatKey, NatBinding}
import org.midonet.midolman.topology.VirtualTopologyActor
import org.midonet.midolman.util.MidolmanSpec
import org.midonet.odp.{Packet, FlowMatch}
import org.midonet.odp.flows.{FlowActions, FlowKeys}
import org.midonet.packets.util.AddressConversions._
import org.midonet.packets.util.PacketBuilder._
import org.midonet.packets.{IPv4Subnet, MAC, IPv4Addr}
import org.midonet.packets._
import org.midonet.sdn.state.ShardedFlowStateTable

@RunWith(classOf[JUnitRunner])
class PingRouterWithNat extends MidolmanSpec {

    registerActors(VirtualTopologyActor -> (() => new VirtualTopologyActor))

    private var nearRouter: UUID = _
    private var farRouter: UUID = _

    private val nearNwAddr = new IPv4Subnet("172.19.0.0", 24)
    private val farNwAddr = new IPv4Subnet("172.20.0.0", 24)

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
    private val snatIp = new IPv4Subnet("42.159.207.7", 32)

    private val pingId: Short = 8756

    override def beforeTest(): Unit = {
        newHost("myself", hostId)

        nearRouter = newRouter("near_router")
        farRouter = newRouter("far_router")

        nearLeftPort = newRouterPort(nearRouter, nearLeftPortMac, nearLeftPortAddr)
        nearRightPort = newRouterPort(nearRouter, nearRightPortMac, nearRightPortAddr)
        farPort = newRouterPort(farRouter, farPortMac, farPortAddr)

        materializePort(nearLeftPort, hostId, "uplink")

        linkPorts(nearRightPort, farPort)

        newRoute(nearRouter, "0.0.0.0", 0,
                 farNwAddr.toNetworkAddress, farNwAddr.getPrefixLen,
                 NextHop.PORT, nearRightPort,
                 new IPv4Addr(Route.NO_GATEWAY).toString, 10)

        newRoute(nearRouter, "0.0.0.0", 0,
                 nearNwAddr.toNetworkAddress, nearNwAddr.getPrefixLen,
                 NextHop.PORT, nearLeftPort,
                 new IPv4Addr(Route.NO_GATEWAY).toString, 10)

        newRoute(farRouter, "0.0.0.0", 0,
                 "0.0.0.0", 0,
                 NextHop.PORT, farPort,
                 new IPv4Addr(Route.NO_GATEWAY).toString, 10)

        val rtrOutChain = newOutboundChainOnRouter("rtrOutChain", nearRouter)
        val rtrInChain = newInboundChainOnRouter("rtrInChain", nearRouter)

        val revSnatCond = new Condition()
        revSnatCond.inPortIds = new HashSet()
        revSnatCond.inPortIds.add(nearRightPort)
        revSnatCond.nwDstIp = snatIp

        newReverseNatRuleOnChain(
            rtrInChain,
            1,
            revSnatCond,
            RuleResult.Action.ACCEPT,
            isDnat = false)

        val snatCond = new Condition()
        snatCond.nwSrcIp = srcIp

        val snatTarget = new NatTarget(snatIp.getAddress, snatIp.getAddress, 1, 10)
        newForwardNatRuleOnChain(
            rtrOutChain,
            1,
            snatCond,
            RuleResult.Action.ACCEPT,
            Set(snatTarget),
            isDnat = false)

        feedArpTable(fetchDevice[Router](nearRouter),
                     srcIp.toNetworkAddress, srcMac)

        fetchRouters(nearRouter, farRouter)
        fetchPorts(nearLeftPort, nearRightPort, farPort)
        fetchTopology(rtrInChain, rtrOutChain)
    }

    val pingReq: Ethernet =
        { eth src srcMac dst nearLeftPortMac } <<
        { ip4 src srcIp.toNetworkAddress dst farPortAddr.toNetworkAddress } <<
        { icmp.echo id pingId }

    scenario("Generated packets are reverse NATed") {
        val table = new ShardedFlowStateTable[NatKey, NatBinding]().addShard()

        val fmatch = new FlowMatch(FlowKeys.fromEthernetPacket(pingReq))
        fmatch.setInputPortNumber(1)
        val packet = new Packet(pingReq, fmatch)

        val workflow = packetWorkflow(
            dpPortToVport = Map(1 -> nearLeftPort),
            natTable = table)

        var passed = false
        mockDpChannel.packetsExecuteSubscribe { (pingReply, actions) =>
            // The original ping reply
            matchIcmp(
                pingReply.getEthernet,
                farPortMac,
                nearRightPortMac,
                farPortAddr.toNetworkAddress,
                snatIp.toNetworkAddress,
                ICMP.TYPE_ECHO_REPLY)

            // The modifications to the original packet
            actions should contain theSameElementsAs List(
                FlowActions.setKey(FlowKeys.ethernet(nearLeftPortMac, srcMac)),
                FlowActions.setKey(FlowKeys.ipv4(farPortAddr.getIntAddress(),
                                                 srcIp.getIntAddress(), 1.toByte,
                                                 0.toByte, 63.toByte, 0.toByte)),
                FlowActions.output(1))
            passed = true
        }

        workflow.receive(HandlePackets(Array(packet)))
        mockDpChannel.packetsSent should have size 1
        passed should be (true)
    }

    private def matchIcmp(
            eth: Ethernet,
            fromMac: MAC,
            toMac: MAC,
            fromIp: IPv4Addr,
            toIp: IPv4Addr,
            `type`: Byte): Unit = {
        eth.getSourceMACAddress should be (fromMac)
        eth.getDestinationMACAddress should be (toMac)
        val ip = eth.getPayload.asInstanceOf[IPv4]
        ip.getSourceIPAddress should be (fromIp)
        ip.getDestinationIPAddress should be (toIp)
        val icmp = ip.getPayload.asInstanceOf[ICMP]
        icmp.getType should be (`type`)
    }
}
