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

import scala.collection.JavaConversions._

import org.junit.runner.RunWith

import org.midonet.sdn.flows.FlowTagger.DeviceTag
import org.scalatest.junit.JUnitRunner
import org.slf4j.helpers.NOPLogger

import org.midonet.midolman.PacketWorkflow._
import org.midonet.midolman.layer3.Route._
import org.midonet.midolman.rules.{Condition, NatTarget, RuleResult}
import org.midonet.midolman.simulation.Simulator.ToPortAction
import org.midonet.midolman.simulation.{RouteBalancer, RouterPort, Router => SimRouter}
import org.midonet.midolman.util.MidolmanSpec
import org.midonet.odp.FlowMatch
import org.midonet.odp.flows._
import org.midonet.packets._
import org.midonet.packets.util.PacketBuilder._
import org.midonet.util.logging.Logger

@RunWith(classOf[JUnitRunner])
class RouterSimulationTest extends MidolmanSpec {

    var router: UUID = _
    var uplinkPort: UUID = _
    var upLinkRoute: UUID = _

    lazy val port1: UUID = makePort(1)
    lazy val port2: UUID = makePort(2)

    val uplinkGatewayAddr = "180.0.1.1"
    val uplinkNwAddr = "180.0.1.0"
    val uplinkNwLen = 24
    val uplinkPortAddr = "180.0.1.2"
    val uplinkMacAddr = MAC.fromString("02:0a:08:06:04:02")

    override def beforeTest(): Unit = {
        router = newRouter("router")
        uplinkPort = newRouterPort(router, uplinkMacAddr, uplinkPortAddr,
                                   uplinkNwAddr, uplinkNwLen)
        materializePort(uplinkPort, hostId, "uplinkPort")
        upLinkRoute = newRoute(router, "0.0.0.0", 0, "0.0.0.0", 0,
            NextHop.PORT, uplinkPort, uplinkGatewayAddr, 1)
    }

    private def makePort(portNum: Int,
                         containerId: Option[UUID] = None,
                         prefixLen: Int = 30): UUID = {
        // Nw address is 10.0.<i>.0/24
        val nwAddr = 0x0a000000 + (portNum << 8)
        val macAddr = MAC.fromString("0a:0b:0c:0d:0e:0" + portNum)
        val portName = "port" + portNum
        // The port will route to 10.0.0.<portnum*4>/30
        val segmentAddr = new IPv4Addr(nwAddr + portNum * 4)
        val portAddr = new IPv4Addr(nwAddr + portNum * 4 + 1)
        val port = newRouterPort(router, macAddr, portAddr.toString,
                                 segmentAddr.toString, 30,
                                 containerId = containerId)
        materializePort(port, hostId, portName)
        // Default route to port based on destination only.  Weight 2.
        newRoute(router, "10.0.0.0", 16, segmentAddr.toString, prefixLen,
            NextHop.PORT, port, new IPv4Addr(NO_GATEWAY).toString,
            2)
        // Anything from 10.0.0.0/16 is allowed through.  Weight 1.
        newRoute(router, "10.0.0.0", 16, segmentAddr.toString, 30,
            NextHop.PORT, port, new IPv4Addr(NO_GATEWAY).toString,
            1)
        // Anything from 11.0.0.0/24 is silently dropped.  Weight 1.
        newRoute(router, "11.0.0.0", 24, segmentAddr.toString, 30,
            NextHop.BLACKHOLE, null, null, 1)
        // Anything from 12.0.0.0/24 is rejected (ICMP filter prohibited).
        newRoute(router, "12.0.0.0", 24, segmentAddr.toString, 30,
            NextHop.REJECT, null, null, 1)
        fetchPorts(port)
        port
    }

    def simRouter: SimRouter = fetchDevice[SimRouter](router)

    private def addressInSegment(port: UUID): IPv4Addr = {
        fetchDevice[RouterPort](port).portAddress4.getAddress.next
    }

    private def setKey[T <: FlowKey](action: FlowAction) =
        action.asInstanceOf[FlowActionSetKey].getFlowKey.asInstanceOf[T]

    scenario("A flow sticks to a route") {
        val routeDst = "21.31.41.51"
        val gateways = List("180.0.1.40", "180.0.1.41", "180.0.1.42")
        gateways foreach { gw =>
            newRoute(router, "0.0.0.0", 0, routeDst, 32,
                     NextHop.PORT, uplinkPort, gw, 1)
        }

        val fmatch = new FlowMatch()
            .setNetworkSrc(IPv4Addr.fromString(uplinkPortAddr))
            .setNetworkDst(IPv4Addr.fromString(routeDst))

        val rb = new RouteBalancer(simRouter.rTable)
        (gateways.indices map { _ =>
           rb.lookup(fmatch, Logger(NOPLogger.NOP_LOGGER)).getNextHopGateway
        } toSet) should have size 1
    }

    scenario("Drops IPv6") {
        val pkt = { eth ether_type IPv6.ETHERTYPE src "01:02:03:04:05:06" dst fetchDevice[RouterPort](port1).portMac }
        simulate(packetContextFor(pkt, uplinkPort))._1 should be (Drop)
    }

    scenario("Forward to uplink") {
        val gwMac = MAC.fromString("aa:bb:aa:cc:dd:cc")
        val ttl = 8.toByte
        val pkt = { eth src MAC.random() dst fetchDevice[RouterPort](port1).portMac } <<
                  { ip4 src addressInSegment(port1) dst "45.44.33.22" ttl ttl } <<
                  { udp src 10 dst 11 }

        feedArpTable(simRouter, IPv4Addr.fromString(uplinkGatewayAddr), gwMac)

        val (simRes, pktCtx) = simulate(packetContextFor(pkt, port1))
        simRes should not be Drop

        pktCtx.virtualFlowActions should have size 3
        val ethKey = setKey[FlowKeyEthernet](pktCtx.virtualFlowActions.get(0))
        ethKey.eth_dst should be (gwMac.getAddress)
        ethKey.eth_src should be (uplinkMacAddr.getAddress)

        val ipKey = setKey[FlowKeyIPv4](pktCtx.virtualFlowActions.get(1))
        ipKey.ipv4_ttl should be (ttl-1)

        pktCtx.virtualFlowActions.get(2) should be (ToPortAction(uplinkPort))
    }

    scenario("Forward from container port, TTL not decremented.") {
        // Create service container.
        val containerId = newServiceContainer()

        // Create container port.
        val cpId = makePort(3, containerId = Some(containerId), prefixLen = 24)
        val cp = fetchDevice[RouterPort](cpId)

        val ttl = 1.toByte
        val pkt = { eth src MAC.random() dst cp.portMac } <<
                  {ip4 src cp.portAddress4.getAddress.next dst "45.44.33.22" ttl ttl } <<
                  { udp src 10 dst 11 }

        val gwMac = MAC.fromString("aa:bb:aa:cc:dd:cc")
        feedArpTable(simRouter, IPv4Addr(uplinkGatewayAddr), gwMac)

        val (simRes, pktCtx) = simulate(packetContextFor(pkt, cpId))
        simRes should not be Drop
        pktCtx.virtualFlowActions should have size 2

        val ethKey = setKey[FlowKeyEthernet](pktCtx.virtualFlowActions.get(0))
        ethKey.eth_dst should be (gwMac.getAddress)
        ethKey.eth_src should be (uplinkMacAddr.getAddress)

        pktCtx.virtualFlowActions.get(1) should be (ToPortAction(uplinkPort))

        // Note: No FlowKeyIPv4 to decrement TTL.
    }

    scenario("Forward to container port, TTL not decremented.") {
        // Create service container.
        val containerId = newServiceContainer()

        // Create container port.
        val cpId = makePort(5, containerId = Some(containerId))
        val cp = fetchDevice[RouterPort](cpId)

        val gwMac = MAC.fromString("aa:bb:aa:cc:dd:cc")
        val addr = addressInSegment(cpId)
        val ttl = 1.toByte
        val pkt = { eth src MAC.random() dst fetchDevice[RouterPort](port1).portMac } <<
          { ip4 src addressInSegment(port1) dst addr ttl ttl } <<
          { udp src 10 dst 11 }

        val mac = MAC.random()
        feedArpTable(simRouter, addr, mac)

        val (simRes, pktCtx) = simulate(packetContextFor(pkt, port1))
        simRes should not be Drop

        pktCtx.virtualFlowActions should have size 2
        val ethKey = setKey[FlowKeyEthernet](pktCtx.virtualFlowActions.get(0))
        ethKey.eth_dst should be (mac.getAddress)
        ethKey.eth_src should be (cp.portMac.getAddress)

        pktCtx.virtualFlowActions.get(1) should be (ToPortAction(cpId))
    }

    scenario("Blackhole route") {
        val pkt = { eth src MAC.random() dst uplinkMacAddr } <<
                  { ip4 src "11.0.0.31" dst addressInSegment(port1) } <<
                  { udp src 10 dst 11 }
        simulate(packetContextFor(pkt, uplinkPort))._1 should be (ErrorDrop)
    }

    scenario("Reject route") {
        // Make a packet that comes in on the uplink port from a nw address in
        // 12.0.0.0/24 and with a nwAddr that matches port 1 - in 10.0.1.6/30
        val fromMac = MAC.random()
        val fromIp = IPv4Addr.fromString("12.0.0.31")
        val pkt = { eth src fromMac dst uplinkMacAddr } <<
                  { ip4 src fromIp dst addressInSegment(port1) } <<
                  { udp src 10 dst 11 }
        val (simRes, _) = simulate(packetContextFor(pkt, uplinkPort))
        simRes should be (ShortDrop)
        matchIcmp(uplinkPort, uplinkMacAddr, fromMac,
                  IPv4Addr.fromString(uplinkPortAddr), fromIp,
                  ICMP.TYPE_UNREACH, ICMP.UNREACH_CODE.UNREACH_FILTER_PROHIB.toByte)
    }

    scenario("Forward between downlinks") {
        val inFromMac = MAC.random
        val inToMac = fetchDevice[RouterPort](port1).portMac
        val outFromMac = fetchDevice[RouterPort](port2).portMac
        val outToMac = MAC.random
        val fromIp = addressInSegment(port1)
        val toIp = addressInSegment(port2)
        val pkt = { eth src inFromMac dst inToMac } <<
                  { ip4 src fromIp dst toIp } <<
                  { udp src 10 dst 11 }

        feedArpTable(simRouter, toIp, outToMac)

        val (simRes, pktCtx) = simulate(packetContextFor(pkt, port1))
        simRes should not be Drop

        pktCtx.virtualFlowActions should have size 3
        val ethKey = setKey[FlowKeyEthernet](pktCtx.virtualFlowActions.get(0))
        ethKey.eth_dst should be (outToMac.getAddress)
        ethKey.eth_src should be (outFromMac.getAddress)

        pktCtx.virtualFlowActions.get(2) should be (ToPortAction(port2))
    }

    scenario("No route") {
        deleteRoute(upLinkRoute)

        val fromMac = MAC.random()
        val fromIp = addressInSegment(port1)
        val port = fetchDevice[RouterPort](port1)
        val pkt = { eth src fromMac dst port.portMac } <<
                  { ip4 src fromIp dst IPv4Addr.fromString("45.44.33.22") } <<
                  { udp src 10 dst 11 }
        val (simRes, _) = simulate(packetContextFor(pkt, port1))
        simRes should be (ShortDrop)
        matchIcmp(port1, port.portMac, fromMac,
                  port.portAddress4.getAddress, fromIp,
                  ICMP.TYPE_UNREACH, ICMP.UNREACH_CODE.UNREACH_NET.toByte)
    }

    scenario("ICMP echo near port") {
        val fromMac = MAC.fromString("01:02:03:04:05:06")
        val fromIp = IPv4Addr.fromString("50.25.50.25")

        feedArpTable(simRouter, IPv4Addr.fromString(uplinkGatewayAddr), fromMac)

        val pkt = { eth src fromMac dst uplinkMacAddr } <<
                  { ip4 src fromIp dst uplinkPortAddr } <<
                  { icmp.echo request }

        val (simRes, _) = simulate(packetContextFor(pkt, uplinkPort))
        simRes should be (NoOp)
        matchIcmp(uplinkPort, uplinkMacAddr, fromMac,
                  IPv4Addr.fromString(uplinkPortAddr), fromIp,
                  ICMP.TYPE_ECHO_REPLY, ICMP.CODE_NONE)
    }

    scenario("ICMP echo far port") {
        val fromMac = MAC.fromString("01:02:03:04:05:06")
        val fromIp = IPv4Addr.fromString("10.0.50.25")

        feedArpTable(simRouter, IPv4Addr.fromString(uplinkGatewayAddr), fromMac)

        val port = fetchDevice[RouterPort](port1)
        val pkt = { eth src fromMac dst uplinkMacAddr } <<
                  { ip4 src fromIp dst port.portAddress4.getAddress } <<
                  { icmp.echo request }

        val (simRes, _) = simulate(packetContextFor(pkt, uplinkPort))
        simRes should be (NoOp)
        matchIcmp(uplinkPort, uplinkMacAddr, fromMac,
                  port.portAddress4.getAddress, fromIp,
                  ICMP.TYPE_ECHO_REPLY, ICMP.CODE_NONE)
    }

    scenario("ICMP echo far port with floating IP") {
        val floatingIp = IPv4Addr.fromString("176.28.127.1")
        val privIp = fetchDevice[RouterPort](port1).portAddress4.getAddress

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

        val (simRes, _) = simulate(packetContextFor(pkt, uplinkPort))
        simRes should be (NoOp)
        matchIcmp(uplinkPort, uplinkMacAddr, fromMac,
                  floatingIp, fromIp, ICMP.TYPE_ECHO_REPLY, ICMP.CODE_NONE)
    }

    scenario("Next hop non-local address") {
        val badGwAddr = "179.0.0.1"
        deleteRoute(upLinkRoute)
        newRoute(router, "0.0.0.0", 0, "0.0.0.0", 0, NextHop.PORT,
                 uplinkPort, badGwAddr, 1)
        val fromMac = MAC.random
        val fromIp = addressInSegment(port1)
        val port = fetchDevice[RouterPort](port1)
        val pkt = { eth src fromMac dst port.portMac } <<
                  { ip4 src fromIp dst "45.44.33.22" } <<
                  { udp src 10 dst 11 }

        val (simRes, _) = simulate(packetContextFor(pkt, port1))
        simRes should be (ErrorDrop)

        matchIcmp(port1, port.portMac, fromMac,
                  port.portAddress4.getAddress, fromIp,
                  ICMP.TYPE_UNREACH, ICMP.UNREACH_CODE.UNREACH_NET.toByte)
    }

    scenario("Unlinked logical port") {
        val logicalPort = newRouterPort(router, MAC.random, "13.13.13.1",
                                        "13.0.0.0", 8)
        newRoute(router, "0.0.0.0", 0, "16.0.0.0", 8,
            NextHop.PORT, logicalPort, "13.13.13.2", 1)

        // Delete uplink route so that the traffic won't get forwarded there.
        deleteRoute(upLinkRoute)

        val fromMac = MAC.random
        val fromIp = addressInSegment(port1)
        val port = fetchDevice[RouterPort](port1)
        val pkt = { eth src fromMac dst port.portMac } <<
                  { ip4 src fromIp dst "16.0.0.1" } <<
                  { udp src 10 dst 11 }

        val (simRes, _) = simulate(packetContextFor(pkt, port1))
        simRes should be (ShortDrop)

        matchIcmp(port1, port.portMac, fromMac,
                  port.portAddress4.getAddress, fromIp,
                  ICMP.TYPE_UNREACH, ICMP.UNREACH_CODE.UNREACH_NET.toByte)
    }

    scenario("Drops L2 traffic") {
        val pkt = { eth src MAC.random dst fetchDevice[RouterPort](port1).portMac }
        val (simRes, _) = simulate(packetContextFor(pkt, port1))
        simRes should be (Drop)
    }

    scenario("Drops VLAN traffic") {
        val inFromMac = MAC.random
        val inToMac = fetchDevice[RouterPort](port1).portMac
        val outToMac = MAC.random
        val fromIp = addressInSegment(port1)
        val toIp = addressInSegment(port2)
        val pkt = { eth src inFromMac dst inToMac vlan 10} <<
                  { ip4 src fromIp dst toIp } <<
                  { udp src 10 dst 11 }

        feedArpTable(simRouter, toIp, outToMac)

        val (simRes, _) = simulate(packetContextFor(pkt, port1))
        simRes should be (Drop)
    }

    scenario("Allows VLAN-0 traffic") {
        val inFromMac = MAC.random
        val inToMac = fetchDevice[RouterPort](port1).portMac
        val outToMac = MAC.random
        val fromIp = addressInSegment(port1)
        val toIp = addressInSegment(port2)
        val pkt = { eth src inFromMac dst inToMac vlan 0} <<
                  { ip4 src fromIp dst toIp } <<
                  { udp src 10 dst 11 }

        feedArpTable(simRouter, toIp, outToMac)

        val (simRes, context) = simulate(packetContextFor(pkt, port1))
        simRes should be (AddVirtualWildcardFlow)
        context.virtualFlowActions should contain (FlowActions.popVLAN())
    }

    scenario("Ping with forward match rule") {
        val chain = newOutboundChainOnRouter("egress chain", router)
        val forwardCond = new Condition()
        forwardCond.matchForwardFlow = true
        newLiteralRuleOnChain(chain, 1, forwardCond, RuleResult.Action.ACCEPT)

        val fromMac = MAC.fromString("01:02:03:04:05:06")
        val fromIp = IPv4Addr.fromString("10.0.50.25")

        feedArpTable(simRouter, IPv4Addr.fromString(uplinkGatewayAddr), fromMac)

        val port = fetchDevice[RouterPort](port1)
        val pkt = { eth src fromMac dst uplinkMacAddr } <<
                  { ip4 src fromIp dst port.portAddress4.getAddress } <<
                  { icmp.echo request }

        val (simRes, _) = simulate(packetContextFor(pkt, uplinkPort))
        simRes should be (NoOp)

        matchIcmp(uplinkPort, uplinkMacAddr, fromMac,
                  port.portAddress4.getAddress, fromIp,
                  ICMP.TYPE_ECHO_REPLY, ICMP.CODE_NONE)
    }

    scenario("Ports can be deactivate and reactivated") {
        deleteRoute(upLinkRoute)

        val fromMac = MAC.random
        val fromIp = addressInSegment(port1)
        val toMac = MAC.random
        val toIp = addressInSegment(port2)

        feedArpTable(simRouter, fromIp, fromMac)
        feedArpTable(simRouter, toIp, toMac)

        val port1dev = fetchDevice[RouterPort](port1)
        val port2dev = fetchDevice[RouterPort](port2)
        // Ping from -> to
        var pkt = { eth src fromMac dst port1dev.portMac } <<
                  { ip4 src fromIp dst toIp } <<
                  { icmp.echo request }
        val (simRes1, pktCtx1) = simulate(packetContextFor(pkt, port1))
        simRes1 should be (AddVirtualWildcardFlow)
        pktCtx1.virtualFlowActions should have size 3
        pktCtx1.virtualFlowActions.get(2) should be (ToPortAction(port2))

        // Ping to -> from
        pkt = { eth src toMac dst port2dev.portMac } <<
              { ip4 src toIp  dst fromIp } <<
              { icmp.echo request }
        val (simRes2, pktCtx2) = simulate(packetContextFor(pkt, port2))
        simRes2 should be (AddVirtualWildcardFlow)
        pktCtx2.virtualFlowActions should have size 3
        pktCtx2.virtualFlowActions.get(2) should be (ToPortAction(port1))

        // Deactivate port2
        deletePort(port2)
        pkt = { eth src fromMac dst port1dev.portMac } <<
              { ip4 src fromIp dst toIp } <<
              { icmp.echo request }
        val (simRes3, _) = simulate(packetContextFor(pkt, port1))
        simRes3 should be (ShortDrop)
        matchIcmp(port1, port1dev.portMac, fromMac,
                  port1dev.portAddress4.getAddress,
                  fromIp, ICMP.TYPE_UNREACH, ICMP.UNREACH_CODE.UNREACH_NET.toByte)

        // Reactivate port2
        val ressurected = makePort(2)
        pkt = { eth src fromMac dst port1dev.portMac } <<
              { ip4 src fromIp dst toIp } <<
              { icmp.echo request }
        val (simRes4, pktCtx4) = simulate(packetContextFor(pkt, port1))
        simRes4 should be (AddVirtualWildcardFlow)
        pktCtx4.virtualFlowActions should have size 3
        pktCtx4.virtualFlowActions.get(2) should be (ToPortAction(ressurected))
    }

    scenario("Route's next hop gateway takes precedence over ARP table") {
        /*
         *   *-------------*         *------------*
         *   |             |         |            |
         * RPIn   Router   RP1-----BP1   Bridge   BP3------RP3
         *   |             |         |            |
         *   *-------------*         *----BP2-----*
         *                                 |
         *                                 |
         *                                RP2
         *
         *   RP1 -> Mac 0a:0a:0a:0a:0a:0a, IP 1.1.1.1
         *   RP2 -> Mac 0a:0b:0b:0b:0b:0b, IP 1.1.1.2
         *   RP3 -> Mac 0a:0c:0c:0c:0c:0c, IP 1.1.1.3
         */
        val bridge = newBridge("weird bridge")

        val bp1 = newBridgePort(bridge)
        val rpInMac = MAC.fromString("01:01:01:01:01:01")
        val rp1Mac = MAC.fromString("0A:0A:0A:0A:0A:0A")
        val rpIn = newRouterPort(router, rpInMac, "1.1.1.0", "1.1.1.0", 24)
        val rp1 = newRouterPort(router, rp1Mac, "1.1.1.1", "1.1.1.0", 24)
        linkPorts(rp1, bp1)
        feedArpTable(simRouter, IPv4Addr.fromString("1.1.1.1"), rp1Mac)

        val r2 = newRouter("router2")
        val bp2 = newBridgePort(bridge)
        val rp2Mac = MAC.fromString("0A:0B:0B:0B:0B:0B")
        val rp2 = newRouterPort(r2, rp2Mac, "1.1.1.2", "1.1.1.0", 24)
        linkPorts(rp2, bp2)
        feedArpTable(simRouter, IPv4Addr.fromString("1.1.1.2"), rp2Mac)

        val r3 = newRouter("router3")
        val bp3 = newBridgePort(bridge)
        val rp3Mac = MAC.fromString("0A:0C:0C:0C:0C:0C")
        val rp3 = newRouterPort(r3, rp3Mac, "1.1.1.3", "1.1.1.0", 24)
        linkPorts(rp3, bp3)
        feedArpTable(simRouter, IPv4Addr.fromString("1.1.1.3"), rp3Mac)

        val ttl = 8.toByte
        val fromMac = MAC.random
        val fromIp = addressInSegment(port1)
        val pkt = { eth src fromMac dst rpInMac } <<
                  { ip4 src fromIp dst IPv4Addr("1.1.1.3") ttl ttl } <<
                  { icmp.echo request }

        // If route has no next hop gateway, next hop should be packet's
        // dest IP (1.1.1.3), and packet should end up in router 3.
        val routeId = newRoute(router, "0.0.0.0", 0, "1.1.1.0", 24,
                               NextHop.PORT, rp1, null, 2)
        val (pktCtx, simRes) = simulateDevice(simRouter, pkt, rpIn)
        simRes shouldBe NoOp
        pktCtx.currentDevice shouldBe r3

        // If route has next hop gateway, next hop should be the next hop
        // gateway (1.1.1.2), and packet should end up in router 2, which drops
        // it due to having no route to packet's dest IP (1.1.1.3).
        deleteRoute(routeId)
        newRoute(router, "0.0.0.0", 0, "1.1.1.0", 24,
                 NextHop.PORT, rp1, "1.1.1.2", 2)
        val (pktCtx2, simRes2) = simulateDevice(simRouter, pkt, rpIn)
        simRes2 shouldBe ShortDrop
        pktCtx2.currentDevice shouldBe r2
    }

    scenario("packet should not enter bridge if outgoing port is also next hop") {
        val bridge = newBridge("weird bridge")
        val bp1 = newBridgePort(bridge)

        val rp1Mac = MAC.fromString("0A:0A:0A:0A:0A:0A")
        val rp1 = newRouterPort(router, rp1Mac, "1.1.1.2", "1.1.1.0", 24)
        linkPorts(rp1, bp1)
        feedArpTable(simRouter, IPv4Addr.fromString("1.1.1.3"), rp1Mac)
        feedArpTable(simRouter, IPv4Addr.fromString("1.1.1.2"), rp1Mac)

        val port1dev = fetchDevice[RouterPort](port1)

        val ttl = 2.toByte
        val fromMac = MAC.random
        val fromIp = addressInSegment(port1)
        val pkt = { eth src fromMac dst port1dev.portMac} <<
                  { ip4 src fromIp dst IPv4Addr("1.1.1.3") ttl ttl } <<
                  { icmp.echo request }
        newRoute(router, "0.0.0.0", 0, "1.1.1.0", 24, NextHop.PORT,
                 rp1, null, 2)

        val (pktCtx, simRes) = simulate(packetContextFor(pkt, port1))
        simRes.flowTags foreach {
            case ft: DeviceTag => ft.deviceId() should not be bridge
            case _ =>
        }
    }

    scenario("Port group match") {
        deleteRoute(upLinkRoute)

        val portGroup1 = newPortGroup("test-pg1")
        newPortGroupMember(portGroup1, port1)
        val portGroup2 = newPortGroup("test-pg2")
        newPortGroupMember(portGroup2, port2)

        val fromMac = MAC.random
        val fromIp = addressInSegment(port1)
        val toMac = MAC.random
        val toIp = addressInSegment(port2)

        feedArpTable(simRouter, fromIp, fromMac)
        feedArpTable(simRouter, toIp, toMac)

        val port1dev = fetchDevice[RouterPort](port1)
        val port2dev = fetchDevice[RouterPort](port2)
        var pkt = { eth src fromMac dst port1dev.portMac } <<
                  { ip4 src fromIp dst toIp } <<
                  { icmp.echo request }

        def validatePass = {
            val (simRes, pktCtx) = simulate(packetContextFor(pkt, port1))
            simRes should be (AddVirtualWildcardFlow)
            pktCtx.virtualFlowActions should contain (ToPortAction(port2))
        }

        def validateDrop = {
            val (simRes, pktCtx) = simulate(packetContextFor(pkt, port1))
            simRes should be (Drop)
        }

        val postChain = newOutboundChainOnRouter("post_routing", router)

        def newRule(isIn: Boolean, pg: UUID, action: RuleResult.Action) = {
            val cond = new Condition()
            if (isIn) {
                cond.inPortGroup = pg
            } else {
                cond.outPortGroup = pg
            }
            newLiteralRuleOnChain(postChain, 1, cond, action)
        }

        validatePass
        newRule(true, portGroup2, RuleResult.Action.DROP)  // should not match
        validatePass
        newRule(false, portGroup1, RuleResult.Action.DROP)  // should not match
        validatePass
        newRule(true, portGroup1, RuleResult.Action.DROP)
        validateDrop
        newRule(false, portGroup2, RuleResult.Action.ACCEPT)
        validatePass
    }

    scenario("nw-dst-rewritten match") {
        deleteRoute(upLinkRoute)

        val fromMac = MAC.random
        val fromIp = addressInSegment(port1)
        val toMac = MAC.random
        val toIp = addressInSegment(port2)

        feedArpTable(simRouter, fromIp, fromMac)
        feedArpTable(simRouter, toIp, toMac)

        val port1dev = fetchDevice[RouterPort](port1)

        def validatePass(ip: IPv4Addr) = {
            var pkt = { eth src fromMac dst port1dev.portMac } <<
                      { ip4 src fromIp dst ip } <<
                      { icmp.echo request }
            val (simRes, pktCtx) = simulate(packetContextFor(pkt, port1))
            simRes should be (AddVirtualWildcardFlow)
            pktCtx.virtualFlowActions should contain (ToPortAction(port2))
        }

        def validateDrop(ip: IPv4Addr) = {
            var pkt = { eth src fromMac dst port1dev.portMac } <<
                      { ip4 src fromIp dst ip } <<
                      { icmp.echo request }
            val (simRes, pktCtx) = simulate(packetContextFor(pkt, port1))
            simRes should be (Drop)
        }

        val preChain = newInboundChainOnRouter("pre_routing", router)
        val postChain = newOutboundChainOnRouter("post_routing", router)

        val floatingIp = IPv4Addr.fromString("176.28.127.1")
        val privIp = toIp
        val dnatCond = new Condition()
        dnatCond.nwDstIp = floatingIp.subnet()
        val dnatTarget = new NatTarget(privIp.toInt, privIp.toInt, 0, 0)
        newForwardNatRuleOnChain(preChain, 1, dnatCond, RuleResult.Action.ACCEPT,
                                 Set(dnatTarget), isDnat = true)

        validatePass(floatingIp)  // This involves nw dst rewrite
        validatePass(toIp)

        val cond = new Condition()
        cond.matchNwDstRewritten = true
        newLiteralRuleOnChain(postChain, 1, cond, RuleResult.Action.DROP)

        validateDrop(floatingIp)  // This involves nw dst rewrite
        validatePass(toIp)
    }

    private def matchIcmp(egressPort: UUID, fromMac: MAC, toMac: MAC,
                          fromIp: IPv4Addr, toIp: IPv4Addr, `type`: Byte,
                          code: Byte): Unit = {
        val pkt = simBackChannel.find[GeneratedLogicalPacket]()
        pkt.egressPort should be (egressPort)
        val genPkt = pkt.eth
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
