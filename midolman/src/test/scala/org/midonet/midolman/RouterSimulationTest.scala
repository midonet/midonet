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

import java.util.{LinkedList, UUID}

import org.slf4j.helpers.NOPLogger
import com.typesafe.scalalogging.Logger

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import org.midonet.cluster.data.{Router => ClusterRouter}
import org.midonet.cluster.data.ports.RouterPort
import org.midonet.midolman.PacketWorkflow._
import org.midonet.midolman.layer3.Route._
import org.midonet.midolman.rules.{RuleResult, NatTarget, Condition}
import org.midonet.midolman.simulation.{Router => SimRouter, RouteBalancer}
import org.midonet.midolman.simulation.PacketEmitter.GeneratedPacket
import org.midonet.midolman.topology.VirtualTopologyActor
import org.midonet.midolman.util.MidolmanSpec
import org.midonet.odp.FlowMatch
import org.midonet.odp.flows._
import org.midonet.packets._
import org.midonet.packets.util.PacketBuilder._
import org.midonet.sdn.flows.VirtualActions.FlowActionOutputToVrnPort

@RunWith(classOf[JUnitRunner])
class RouterSimulationTest extends MidolmanSpec {

    registerActors(VirtualTopologyActor -> (() => new VirtualTopologyActor))

    var router: ClusterRouter = _
    var uplinkPort: RouterPort = _
    var upLinkRoute: UUID = _

    lazy val port1: RouterPort = makePort(1)
    lazy val port2: RouterPort = makePort(2)

    val uplinkGatewayAddr = "180.0.1.1"
    val uplinkNwAddr = "180.0.1.0"
    val uplinkNwLen = 24
    val uplinkPortAddr = "180.0.1.2"
    val uplinkMacAddr = MAC.fromString("02:0a:08:06:04:02")

    val generatedPackets = new LinkedList[GeneratedPacket]()

    override def beforeTest(): Unit = {
        router = newRouter("router")
        uplinkPort = newRouterPort(router, uplinkMacAddr, uplinkPortAddr,
                                   uplinkNwAddr, uplinkNwLen)
        materializePort(uplinkPort, hostId, "uplinkPort")
        upLinkRoute = newRoute(router, "0.0.0.0", 0, "0.0.0.0", 0,
            NextHop.PORT, uplinkPort.getId, uplinkGatewayAddr, 1)
    }

    private def makePort(portNum: Int) = {
        // Nw address is 10.0.<i>.0/24
        val nwAddr = 0x0a000000 + (portNum << 8)
        val macAddr = MAC.fromString("0a:0b:0c:0d:0e:0" + portNum)
        val portName = "port" + portNum
        // The port will route to 10.0.0.<portnum*4>/30
        val segmentAddr = new IPv4Addr(nwAddr + portNum * 4)
        val portAddr = new IPv4Addr(nwAddr + portNum * 4 + 1)
        val port = newRouterPort(router, macAddr, portAddr.toString,
                                         segmentAddr.toString, 30)
        materializePort(port, hostId, portName)
        // Default route to port based on destination only.  Weight 2.
        newRoute(router, "10.0.0.0", 16, segmentAddr.toString, 30,
            NextHop.PORT, port.getId, new IPv4Addr(NO_GATEWAY).toString,
            2)
        // Anything from 10.0.0.0/16 is allowed through.  Weight 1.
        newRoute(router, "10.0.0.0", 16, segmentAddr.toString, 30,
            NextHop.PORT, port.getId, new IPv4Addr(NO_GATEWAY).toString,
            1)
        // Anything from 11.0.0.0/24 is silently dropped.  Weight 1.
        newRoute(router, "11.0.0.0", 24, segmentAddr.toString, 30,
            NextHop.BLACKHOLE, null, null, 1)
        // Anything from 12.0.0.0/24 is rejected (ICMP filter prohibited).
        newRoute(router, "12.0.0.0", 24, segmentAddr.toString, 30,
            NextHop.REJECT, null, null, 1)
        fetchTopology(port)
        port
    }

    def simRouter: SimRouter = fetchDevice(router)

    private def addressInSegment(port: RouterPort) : IPv4Addr =
        IPv4Addr.fromString(port.getPortAddr).next

    private def setKey[T <: FlowKey](action: FlowAction) =
        action.asInstanceOf[FlowActionSetKey].getFlowKey.asInstanceOf[T]

    scenario("Balances routes") {
        val routeDst = "21.31.41.51"
        val gateways = List("180.0.1.40", "180.0.1.41", "180.0.1.42")
        gateways foreach { gw =>
            newRoute(router, "0.0.0.0", 0, routeDst, 32,
                     NextHop.PORT, uplinkPort.getId, gw, 1)
        }

        val fmatch = new FlowMatch()
            .setNetworkSrc(IPv4Addr.fromString(uplinkPortAddr))
            .setNetworkDst(IPv4Addr.fromString(routeDst))

        val rb = new RouteBalancer(simRouter.rTable)
        (0 until gateways.length) map { _ =>
           rb.lookup(fmatch, Logger(NOPLogger.NOP_LOGGER)).getNextHopGateway
        } should contain theSameElementsAs gateways
    }

    scenario("Drops IPv6") {
        val pkt = { eth ether_type IPv6.ETHERTYPE src "01:02:03:04:05:06" dst port1.getHwAddr }
        simulate(packetContextFor(pkt, uplinkPort.getId))._1 should be (Drop)
    }

    scenario("Forward to uplink") {
        val gwMac = MAC.fromString("aa:bb:aa:cc:dd:cc")
        val ttl = 8.toByte
        val pkt = { eth src MAC.random() dst port1.getHwAddr } <<
                  { ip4 src addressInSegment(port1) dst "45.44.33.22" ttl ttl } <<
                  { udp src 10 dst 11 }

        feedArpTable(simRouter, IPv4Addr.fromString(uplinkGatewayAddr), gwMac)

        val (simRes, pktCtx) = simulate(packetContextFor(pkt, port1.getId))
        simRes should not be Drop

        pktCtx.virtualFlowActions should have size 3
        val ethKey = setKey[FlowKeyEthernet](pktCtx.virtualFlowActions.get(0))
        ethKey.eth_dst should be (gwMac.getAddress)
        ethKey.eth_src should be (uplinkMacAddr.getAddress)

        val ipKey = setKey[FlowKeyIPv4](pktCtx.virtualFlowActions.get(1))
        ipKey.ipv4_ttl should be (ttl-1)

        pktCtx.virtualFlowActions.get(2) should be (FlowActionOutputToVrnPort(uplinkPort.getId))
    }

    scenario("Blackhole route") {
        val pkt = { eth src MAC.random() dst uplinkMacAddr } <<
                  { ip4 src "11.0.0.31" dst addressInSegment(port1) } <<
                  { udp src 10 dst 11 }
        simulate(packetContextFor(pkt, uplinkPort.getId))._1 should be (ErrorDrop)
    }

    scenario("Reject route") {
        // Make a packet that comes in on the uplink port from a nw address in
        // 12.0.0.0/24 and with a nwAddr that matches port 1 - in 10.0.1.6/30
        val fromMac = MAC.random()
        val fromIp = IPv4Addr.fromString("12.0.0.31")
        val pkt = { eth src fromMac dst uplinkMacAddr } <<
                  { ip4 src fromIp dst addressInSegment(port1) } <<
                  { udp src 10 dst 11 }
        val (simRes, _) = simulate(packetContextFor(pkt, uplinkPort.getId, generatedPackets))
        simRes should be (ShortDrop)
        matchIcmp(uplinkPort, uplinkMacAddr, fromMac,
                  IPv4Addr.fromString(uplinkPortAddr), fromIp,
                  ICMP.TYPE_UNREACH, ICMP.UNREACH_CODE.UNREACH_FILTER_PROHIB.toByte)
    }

    scenario("Forward between downlinks") {
        val inFromMac = MAC.random
        val inToMac = port1.getHwAddr
        val outFromMac = port2.getHwAddr
        val outToMac = MAC.random
        val fromIp = addressInSegment(port1)
        val toIp = addressInSegment(port2)
        val pkt = { eth src inFromMac dst inToMac } <<
                  { ip4 src fromIp dst toIp } <<
                  { udp src 10 dst 11 }

        feedArpTable(simRouter, toIp, outToMac)

        val (simRes, pktCtx) = simulate(packetContextFor(pkt, port1.getId))
        simRes should not be Drop

        pktCtx.virtualFlowActions should have size 3
        val ethKey = setKey[FlowKeyEthernet](pktCtx.virtualFlowActions.get(0))
        ethKey.eth_dst should be (outToMac.getAddress)
        ethKey.eth_src should be (outFromMac.getAddress)

        pktCtx.virtualFlowActions.get(2) should be (FlowActionOutputToVrnPort(port2.getId))
    }

    scenario("No route") {
        clusterDataClient.routesDelete(upLinkRoute)

        val fromMac = MAC.random()
        val fromIp = addressInSegment(port1)
        val pkt = { eth src fromMac dst port1.getHwAddr } <<
                  { ip4 src fromIp dst IPv4Addr.fromString("45.44.33.22") } <<
                  { udp src 10 dst 11 }
        val (simRes, _) = simulate(packetContextFor(pkt, port1.getId, generatedPackets))
        simRes should be (ShortDrop)
        matchIcmp(port1, port1.getHwAddr, fromMac,
                  IPv4Addr.fromString(port1.getPortAddr), fromIp,
                  ICMP.TYPE_UNREACH, ICMP.UNREACH_CODE.UNREACH_NET.toByte)
    }

    scenario("ICMP echo near port") {
        val fromMac = MAC.fromString("01:02:03:04:05:06")
        val fromIp = IPv4Addr.fromString("50.25.50.25")

        feedArpTable(simRouter, IPv4Addr.fromString(uplinkGatewayAddr), fromMac)

        val pkt = { eth src fromMac dst uplinkMacAddr } <<
                  { ip4 src fromIp dst uplinkPortAddr } <<
                  { icmp.echo request }

        val (simRes, _) = simulate(packetContextFor(pkt, uplinkPort.getId, generatedPackets))
        simRes should be (NoOp)
        matchIcmp(uplinkPort, uplinkMacAddr, fromMac,
                  IPv4Addr.fromString(uplinkPortAddr), fromIp,
                  ICMP.TYPE_ECHO_REPLY, ICMP.CODE_NONE)
    }

    scenario("ICMP echo far port") {
        val fromMac = MAC.fromString("01:02:03:04:05:06")
        val fromIp = IPv4Addr.fromString("10.0.50.25")

        feedArpTable(simRouter, IPv4Addr.fromString(uplinkGatewayAddr), fromMac)

        val pkt = { eth src fromMac dst uplinkMacAddr } <<
                  { ip4 src fromIp dst port1.getPortAddr } <<
                  { icmp.echo request }

        val (simRes, _) = simulate(packetContextFor(pkt, uplinkPort.getId, generatedPackets))
        simRes should be (NoOp)
        matchIcmp(uplinkPort, uplinkMacAddr, fromMac,
                  IPv4Addr.fromString(port1.getPortAddr), fromIp,
                  ICMP.TYPE_ECHO_REPLY, ICMP.CODE_NONE)
    }

    scenario("ICMP echo far port with floating IP") {
        val floatingIp = IPv4Addr.fromString("176.28.127.1")
        val privIp = IPv4Addr.fromString(port1.getPortAddr)

        val dnatCond = new Condition()
        dnatCond.nwDstIp = floatingIp.subnet()
        val dnatTarget = new NatTarget(privIp.toInt, privIp.toInt, 0, 0)
        val preChain = newInboundChainOnRouter("pre_routing", router)
        val postChain = newOutboundChainOnRouter("post_routing", router)
        newForwardNatRuleOnChain(preChain, 1, dnatCond, RuleResult.Action.ACCEPT,
                                 Set(dnatTarget), isDnat = true)
        val snatCond = new Condition()
        snatCond.nwSrcIp = privIp.subnet()
        val snatTarget = new NatTarget(floatingIp.toInt, floatingIp.toInt, 0, 0)
        newForwardNatRuleOnChain(postChain, 1, snatCond, RuleResult.Action.ACCEPT,
                                 Set(snatTarget), isDnat = false)

        val fromMac = MAC.random()
        val fromIp = IPv4Addr.fromString("180.0.1.23")

        feedArpTable(simRouter, IPv4Addr.fromString(uplinkGatewayAddr), fromMac)

        val pkt = { eth src fromMac dst uplinkMacAddr } <<
                  { ip4 src fromIp dst floatingIp } <<
                  { icmp.echo request }

        val (simRes, _) = simulate(packetContextFor(pkt, uplinkPort.getId, generatedPackets))
        simRes should be (NoOp)
        matchIcmp(uplinkPort, uplinkMacAddr, fromMac,
                  floatingIp, fromIp, ICMP.TYPE_ECHO_REPLY, ICMP.CODE_NONE)
    }

    scenario("Next hop non-local address") {
        val badGwAddr = "179.0.0.1"
        clusterDataClient.routesDelete(upLinkRoute)
        newRoute(router, "0.0.0.0", 0, "0.0.0.0", 0, NextHop.PORT,
                 uplinkPort.getId, badGwAddr, 1)
        val fromMac = MAC.random
        val fromIp = addressInSegment(port1)

        val pkt = { eth src fromMac dst port1.getHwAddr } <<
                  { ip4 src fromIp dst "45.44.33.22" } <<
                  { udp src 10 dst 11 }

        val (simRes, _) = simulate(packetContextFor(pkt, port1.getId, generatedPackets))
        simRes should be (ErrorDrop)

        matchIcmp(port1, port1.getHwAddr, fromMac,
                  IPv4Addr.fromString(port1.getPortAddr), fromIp,
                  ICMP.TYPE_UNREACH, ICMP.UNREACH_CODE.UNREACH_NET.toByte)
    }

    scenario("Unlinked logical port") {
        val logicalPort = newRouterPort(router, MAC.random, "13.13.13.1",
                                        "13.0.0.0", 8)
        newRoute(router, "0.0.0.0", 0, "16.0.0.0", 8,
            NextHop.PORT, logicalPort.getId, "13.13.13.2", 1)

        // Delete uplink route so that the traffic won't get forwarded there.
        deleteRoute(upLinkRoute)

        val fromMac = MAC.random
        val fromIp = addressInSegment(port1)

        val pkt = { eth src fromMac dst port1.getHwAddr } <<
                  { ip4 src fromIp dst "16.0.0.1" } <<
                  { udp src 10 dst 11 }

        val (simRes, _) = simulate(packetContextFor(pkt, port1.getId, generatedPackets))
        simRes should be (ShortDrop)

        matchIcmp(port1, port1.getHwAddr, fromMac,
                  IPv4Addr.fromString(port1.getPortAddr), fromIp,
                  ICMP.TYPE_UNREACH, ICMP.UNREACH_CODE.UNREACH_NET.toByte)
    }

    scenario("Drops VLAN traffic") {
        val pkt = { eth src MAC.random dst port1.getHwAddr vlan 10 }
        val (simRes, _) = simulate(packetContextFor(pkt, port1.getId))
        simRes should be (Drop)
    }

    scenario("Allows VLAN-0 traffic") {
        val pkt = { eth src MAC.random dst port1.getHwAddr vlan 0 }
        val (simRes, _) = simulate(packetContextFor(pkt, port1.getId))
        simRes should be (AddVirtualWildcardFlow)
    }

    scenario("Ping with forward match rule") {
        val chain = newOutboundChainOnRouter("egress chain", router)
        val forwardCond = new Condition()
        forwardCond.matchForwardFlow = true
        newLiteralRuleOnChain(chain, 1, forwardCond, RuleResult.Action.ACCEPT)

        val fromMac = MAC.fromString("01:02:03:04:05:06")
        val fromIp = IPv4Addr.fromString("10.0.50.25")

        feedArpTable(simRouter, IPv4Addr.fromString(uplinkGatewayAddr), fromMac)

        val pkt = { eth src fromMac dst uplinkMacAddr } <<
                  { ip4 src fromIp dst port1.getPortAddr } <<
                  { icmp.echo request }

        val (simRes, _) = simulate(packetContextFor(pkt, uplinkPort.getId, generatedPackets))
        simRes should be (NoOp)

        matchIcmp(uplinkPort, uplinkMacAddr, fromMac,
                  IPv4Addr.fromString(port1.getPortAddr), fromIp,
                  ICMP.TYPE_ECHO_REPLY, ICMP.CODE_NONE)
    }

    scenario("Ports can be deactivate and reactivated") {
        clusterDataClient.routesDelete(upLinkRoute)

        val fromMac = MAC.random
        val fromIp = addressInSegment(port1)
        val toMac = MAC.random
        val toIp = addressInSegment(port2)

        feedArpTable(simRouter, fromIp, fromMac)
        feedArpTable(simRouter, toIp, toMac)

        // Ping from -> to
        var pkt = { eth src fromMac dst port1.getHwAddr } <<
                  { ip4 src fromIp dst toIp } <<
                  { icmp.echo request }
        val (simRes1, pktCtx1) = simulate(packetContextFor(pkt, port1.getId))
        simRes1 should be (AddVirtualWildcardFlow)
        pktCtx1.virtualFlowActions should have size 3
        pktCtx1.virtualFlowActions.get(2) should be (FlowActionOutputToVrnPort(port2.getId))

        // Ping to -> from
        pkt = { eth src toMac dst port2.getHwAddr } <<
              { ip4 src toIp  dst fromIp } <<
              { icmp.echo request }
        val (simRes2, pktCtx2) = simulate(packetContextFor(pkt, port2.getId))
        simRes2 should be (AddVirtualWildcardFlow)
        pktCtx2.virtualFlowActions should have size 3
        pktCtx2.virtualFlowActions.get(2) should be (FlowActionOutputToVrnPort(port1.getId))

        // Deactivate port2
        clusterDataClient.portsDelete(port2.getId)
        pkt = { eth src fromMac dst port1.getHwAddr } <<
              { ip4 src fromIp dst toIp } <<
              { icmp.echo request }
        val (simRes3, _) = simulate(packetContextFor(pkt, port1.getId, generatedPackets))
        simRes3 should be (ShortDrop)
        matchIcmp(port1, port1.getHwAddr, fromMac, IPv4Addr(port1.getPortAddr),
                  fromIp, ICMP.TYPE_UNREACH, ICMP.UNREACH_CODE.UNREACH_NET.toByte)

        // Reactivate port2
        val ressurected = makePort(2)
        pkt = { eth src fromMac dst port1.getHwAddr } <<
              { ip4 src fromIp dst toIp } <<
              { icmp.echo request }
        val (simRes4, pktCtx4) = simulate(packetContextFor(pkt, port1.getId))
        simRes4 should be (AddVirtualWildcardFlow)
        pktCtx4.virtualFlowActions should have size 3
        pktCtx4.virtualFlowActions.get(2) should be (FlowActionOutputToVrnPort(ressurected.getId))
    }

    private def matchIcmp(egressPort: RouterPort, fromMac: MAC, toMac: MAC,
                          fromIp: IPv4Addr, toIp: IPv4Addr, `type`: Byte,
                          code: Byte): Unit = {
        generatedPackets should have size 1
        generatedPackets.get(0).egressPort should be (egressPort.getId)
        val genPkt = generatedPackets.get(0).eth
        genPkt.getSourceMACAddress should be (fromMac)
        genPkt.getDestinationMACAddress should be (toMac)
        val genIp = genPkt.getPayload.asInstanceOf[IPv4]
        genIp.getSourceIPAddress should be (fromIp)
        genIp.getDestinationIPAddress should be (toIp)
        val genIcmp = genIp.getPayload.asInstanceOf[ICMP]
        genIcmp.getCode should be (code)
        genIcmp.getType should be (`type`)
    }
}
