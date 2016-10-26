/*
 * Copyright 2014-2015 Midokura SARL
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

import java.lang.{Short => JShort}
import java.util.UUID

import org.midonet.odp.FlowMatch

import scala.collection.JavaConversions._
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.midonet.midolman.PacketWorkflow._
import org.midonet.midolman.rules.{Condition, RuleResult}
import org.midonet.midolman.simulation.Simulator.ToPortAction
import org.midonet.midolman.simulation.{Bridge, PacketContext}
import org.midonet.midolman.topology.VirtualTopology
import org.midonet.midolman.util.MidolmanSpec
import org.midonet.odp.flows.{FlowActionSetKey, FlowKey, FlowKeyIPv4}
import org.midonet.packets._
import org.midonet.packets.util.PacketBuilder._
import org.midonet.util.concurrent._


@RunWith(classOf[JUnitRunner])
class BridgeSimulationTest extends MidolmanSpec {

    private var vt: VirtualTopology =_

    private var port1OnHost1: UUID = _
    private var port2OnHost1: UUID = _
    private var port3OnHost1: UUID = _
    private var portOnHost2: UUID = _
    private var portOnHost3: UUID = _


    private var qosPolicy1: UUID = _
    private var dscpRule1: UUID = _
    private var port4OnHost1WithQos: UUID = _

    private var bridge: UUID = _
    private var bridgeDevice: Bridge = _


    val host1Ip = IPv4Addr("192.168.100.1")
    val host2Ip = IPv4Addr("192.168.125.1")
    val host3Ip = IPv4Addr("192.168.150.1")

    val port2OnHost1Mac = "0a:fe:88:70:33:ab"

    override def beforeTest(): Unit ={

        vt = injector.getInstance(classOf[VirtualTopology])

        val tunnelZone = greTunnelZone("default")

        val host1 = hostId
        val host2 = newHost("host2")
        val host3 = newHost("host3")

        bridge = newBridge("bridge")

        qosPolicy1 = newQosPolicy()
        dscpRule1 = newQosDscpRule(qosPolicy1, dscpMark = 22)

        port1OnHost1 = newBridgePort(bridge)
        port2OnHost1 = newBridgePort(bridge)
        port3OnHost1 = newBridgePort(bridge)
        portOnHost2 = newBridgePort(bridge)
        portOnHost3 = newBridgePort(bridge)
        port4OnHost1WithQos = newBridgePort(bridge,
                                            qosPolicyId = Some(qosPolicy1))

        materializePort(port1OnHost1, host1, "port1")
        materializePort(portOnHost2, host2, "port2")
        materializePort(portOnHost3, host3, "port3")
        materializePort(port2OnHost1, host1, "port4")
        materializePort(port3OnHost1, host1, "port5")
        materializePort(port4OnHost1WithQos, host1, "port6")

        List(host1, host2, host3).zip(List(host1Ip, host2Ip, host3Ip)).foreach{
            case (host, ip) =>
                addTunnelZoneMember(tunnelZone, host, ip)
        }

        fetchPorts(port1OnHost1, portOnHost2, portOnHost3,
                   port2OnHost1, port3OnHost1, port4OnHost1WithQos)

        bridgeDevice = fetchDevice[Bridge](bridge)

    }

    /**
      * All frames generated from this test will get the vlan tags set in this
      * list. The bridge (in this case it's a VUB) should simply let them pass
      * and apply mac-learning based on the default vlan id (0).
      *
      * Here the list is simply empty, but just extending this test and
      * overriding the method with a different list you get all the test cases
      * but with vlan-tagged traffic.
      *
      * At the bottom there are a couple of classes that add vlan ids to the
      * same test cases.
      *
      * See MN-200
      *
      */
    def networkVlans: List[JShort] = List()

    scenario("Malformed L3 packet") {
        val malformed = eth mac "02:11:22:33:44:10" -> "02:11:22:33:44:20"
        malformed << payload("00:00")
        malformed ether_type IPv4.ETHERTYPE vlans networkVlans

        val (pktCtx, action) = simulateDevice(bridgeDevice,
                                              malformed, port1OnHost1)
        verifyFloodAction(action, pktCtx)
    }

    scenario("bridge simulation for normal packet") {
        val srcMac = "02:11:22:33:44:10"
        val ethPkt = { eth src srcMac dst "02:11:22:33:44:11" } <<
            { ip4 src "10.0.1.10" dst "10.0.1.11" } <<
            { udp src 10 dst 11 } << payload("My UDP packet")
        ethPkt.setVlanIDs(networkVlans)

        val (pktCtx, action) = simulateDevice(bridgeDevice,
                                              ethPkt, port1OnHost1)
        verifyFloodAction(action, pktCtx)

        verifyMacLearned(srcMac, port1OnHost1)
    }

    scenario("inbound chains not applied to vlan traffic") {
        // setup chains that drop all UDP traffic both at pre and post
        val udpCond = new Condition()
        udpCond.nwProto = Byte.box(UDP.PROTOCOL_NUMBER)

        val preChain = newInboundChainOnBridge("brFilter-in", bridge)
        newLiteralRuleOnChain(preChain, 1,udpCond, RuleResult.Action.DROP)

        // refetch device to pick up chain
        bridgeDevice = fetchDevice[Bridge](bridge)
        checkTrafficWithDropChains()
    }

    scenario("outbound chains not applied to vlan traffic") {
        // setup chains that drop all UDP traffic both at pre and post
        val udpCond = new Condition()
        udpCond.nwProto = Byte.box(UDP.PROTOCOL_NUMBER)

        val postChain = newOutboundChainOnBridge("brFilter-out", bridge)
        newLiteralRuleOnChain(postChain, 1, udpCond, RuleResult.Action.DROP)

        // refetch device to pick up chain
        bridgeDevice = fetchDevice[Bridge](bridge)
        checkTrafficWithDropChains()
    }

    /**
     * Use after setting chains that drop UDP traffic on a bridge. The method
     * will send traffic through the bridge and expect it dropped (by matching
     * on the chains) if it is vlan-tagged, but not dropped otherwise.
     */
    private def checkTrafficWithDropChains() {

        val ethPkt = { eth src "0a:fe:88:70:44:55" dst "ff:ff:ff:ff:ff:ff" } <<
            { ip4 src "10.10.10.10" dst "10.11.11.11" } <<
            { udp src 10 dst 12 } << payload("Test UDP packet")
        ethPkt.setVlanIDs(networkVlans)

        log.info("Testing traffic with vlans: {}", networkVlans)

        val (pktCtx, action) = simulateDevice(bridgeDevice,
                                              ethPkt, port1OnHost1)

        if (networkVlans.isEmpty) {
            action should be (Drop)
        } else {
            verifyFloodAction(action, pktCtx)
        }
    }

    scenario("ethernet broadcast on bridge") {
        val srcMac = "0a:fe:88:70:44:55"
        val ethPkt = { eth src srcMac dst "ff:ff:ff:ff:ff:ff" } <<
            { ip4 src "10.10.10.10" dst "10.11.11.11" } <<
            { udp src 10 dst 12 } << payload("Test UDP packet")
        ethPkt.setVlanIDs(networkVlans)

        val (pktCtx, action) = simulateDevice(bridgeDevice,
                                              ethPkt, port1OnHost1)
        verifyFloodAction(action, pktCtx)

        //Our source MAC should also be learned
        verifyMacLearned(srcMac, port1OnHost1)
    }

    scenario("ARP for peer router port") {
        Given("A source and destination endpoint")
        val srcMac = MAC.fromString("0a:fe:88:90:22:33")
        val dstMac = MAC.fromString("0a:fe:88:90:22:43")
        val srcIp = IPv4Addr("10.10.10.11")
        val dstIp = IPv4Subnet.fromCidr("10.11.11.13/24")

        And("A new bridge port peered to a router port")
        val router = newRouter("router")
        val routerPort = newRouterPort(router, dstMac, dstIp)
        val bridgePort = newBridgePort(bridge)
        linkPorts(bridgePort, routerPort)

        And("An ARP packet from source to destination")
        val ethPkt = { eth src srcMac dst eth_bcast } <<
                        { arp.req.mac(srcMac -> eth_bcast)
                            .ip(srcIp --> dstIp.getAddress) }
        ethPkt.setVlanIDs(networkVlans)

        When("Requesting the updated bridge")
        VirtualTopology.clear()
        bridgeDevice = fetchDevice[Bridge](bridge)

        And("Simulating the packet")
        val (pktCtx, action) = simulateDevice(bridgeDevice,
                                              ethPkt, port1OnHost1)

        Then("The bridge should generate an ARP response")
        verifyGeneratedArp(action, pktCtx, port1OnHost1, dstMac, srcMac,
                           dstIp.getAddress, srcIp)
    }

    scenario("ARP for pre-seeded MAC") {
        Given("A source and destination endpoint")
        val srcMac = MAC.fromString("0a:fe:88:90:22:33")
        val dstMac = MAC.fromString("0a:fe:88:90:22:44")
        val srcIp = IPv4Addr("10.10.10.11")
        val dstIp = IPv4Addr("10.11.11.14")

        And("The destination IP-MAC are preseeded at the bridge")
        vt.stateTables.bridgeArpTable(bridgeDevice.id)
                      .addPersistent(dstIp, dstMac).await()

        And("An ARP packet from source to destination")
        val ethPkt = { eth src srcMac dst eth_bcast } <<
                         { arp.req.mac(srcMac -> eth_bcast)
                             .ip(srcIp --> dstIp) }
        ethPkt.setVlanIDs(networkVlans)

        When("Requesting the updated bridge")
        VirtualTopology.clear()
        bridgeDevice = fetchDevice[Bridge](bridge)

        And("Simulating the packet")
        val (pktCtx, action) = simulateDevice(bridgeDevice,
                                              ethPkt, port1OnHost1)

        Then("The bridge should generate an ARP response")
        verifyGeneratedArp(action, pktCtx, port1OnHost1, dstMac, srcMac,
                           dstIp, srcIp)
    }

    scenario("broadcast arp on bridge") {
        val srcMac = "0a:fe:88:90:22:33"
        val ethPkt = { eth src srcMac dst eth_bcast } <<
            { arp.req.mac(srcMac -> eth_bcast)
                 .ip("10.10.10.11" --> "10.11.11.10") }
        ethPkt.setVlanIDs(networkVlans)

        val (pktCtx, action) = simulateDevice(bridgeDevice,
                                              ethPkt, port1OnHost1)
        verifyFloodAction(action, pktCtx)

        //Our source MAC should also be learned
        verifyMacLearned(srcMac, port1OnHost1)
    }

    scenario("multicast destination ethernet on bridge") {
        val srcMac = "0a:fe:88:90:22:33"
        val ethPkt = { eth src srcMac dst "01:00:cc:cc:dd:dd" } <<
            { ip4 src "10.10.10.11" dst "10.11.11.10" } <<
            { udp src 10 dst 12 } << payload("Test UDP Packet")
        ethPkt.setVlanIDs(networkVlans)

        val (pktCtx, action) = simulateDevice(bridgeDevice,
                                              ethPkt, port1OnHost1)
        verifyFloodAction(action, pktCtx)

        //Our source MAC should also be learned
        verifyMacLearned(srcMac, port1OnHost1)
    }

    scenario("multicast src ethernet on bridge") {
        val ethPkt = { eth src "ff:54:ce:50:44:ce" dst "0a:de:57:16:a3:06" } <<
            { ip4 src "10.10.10.12" dst "10.11.11.12" } <<
            { udp src 10 dst 12 } << payload("Test UDP packet")
        ethPkt.setVlanIDs(networkVlans)

        val (pktCtx, action) = simulateDevice(bridgeDevice,
                                              ethPkt, port1OnHost1)
        action should be (Drop)
    }

    scenario("mac migration on bridge") {
        val srcMac = "02:13:66:77:88:99"
        var ethPkt = { eth src srcMac dst "02:11:22:33:44:55" } <<
            { ip4 src "10.0.1.10" dst "10.0.1.11" } <<
            { udp src 10 dst 11 } << payload("My UDP packet")
        ethPkt.setVlanIDs(networkVlans)

        val bridgeDevice: Bridge = fetchDevice[Bridge](bridge)
        val (pktCtx, action) = simulateDevice(bridgeDevice,
                                              ethPkt, port1OnHost1)
        verifyFloodAction(action, pktCtx)

        verifyMacLearned(srcMac, port1OnHost1)

        /*
         * MAC moved from port1OnHost1 to port3OnHost1
         * frame is going toward port2OnHost1 (the learned MAC from
         * verifyMacLearned)
         */
        ethPkt = { eth src srcMac dst port2OnHost1Mac } <<
            { ip4 src "10.0.1.10" dst "10.0.1.11" } <<
            { udp src 10 dst 11 } << payload("My UDP packet")
        ethPkt.setVlanIDs(networkVlans)

        val (pktCtx2, action2) = simulateDevice(bridgeDevice,
                                                ethPkt, port3OnHost1)
        action should be (AddVirtualWildcardFlow)
        pktCtx2.virtualFlowActions should have size (1)
        pktCtx2.virtualFlowActions.head should be (ToPortAction(port2OnHost1))

        verifyMacLearned(srcMac, port3OnHost1)
    }

    scenario("dscp mark is added to IPv4 packets") {
        val srcMac = "02:11:22:33:44:10"
        val ethPkt = { eth src srcMac dst "02:11:22:33:44:11" } <<
            { ip4 src "10.0.1.10" dst "10.0.1.11" diff_serv 0 } <<
            { udp src 10 dst 11 } << payload("My UDP packet")
        ethPkt.setVlanIDs(networkVlans)
        ethPkt.setEtherType(IPv4.ETHERTYPE)
        val ctx = packetContextFor(ethPkt, port4OnHost1WithQos)
        ctx.origMatch.setEtherType(IPv4.ETHERTYPE)

        val (result, ctxOut) = simulate(ctx)

        verifyDSCP(ctxOut, 22)
    }

    scenario("dscp mark is kept on IPv4 packets when ToS is same as DSCP mark") {
        val srcMac = "02:11:22:33:44:10"
        val ethPkt = { eth src srcMac dst "02:11:22:33:44:11" } <<
            { ip4 src "10.0.1.10" dst "10.0.1.11" diff_serv 22 } <<
            { udp src 10 dst 11 } << payload("My UDP packet")
        ethPkt.setVlanIDs(networkVlans)
        val ctx = packetContextFor(ethPkt, port4OnHost1WithQos)
        ctx.origMatch.setEtherType(IPv4.ETHERTYPE)

        val (result, ctxOut) = simulate(ctx)

        verifyDSCP(ctxOut, 22, requireSetKeyAction=false)
    }

    scenario("dscp mark is not added to non-IPv4 packets") {
        val srcMac = "02:11:22:33:44:10"
        val ethPkt = { eth src srcMac dst eth_bcast } <<
            { arp.req.mac(srcMac -> eth_bcast)
                .ip("10.10.10.11" --> "10.11.11.10") }
        val ctx = packetContextFor(ethPkt, port4OnHost1WithQos)
        ctx.origMatch.setEtherType(ARP.ETHERTYPE)

        val (result, ctxOut) = simulate(ctx)

        verifyNoDSCP(ctxOut)
    }
    /*
     * In this test, always assume input port is port2OnHost1 (keep src mac
     * consistent), dst mac is variable
     */
    private def verifyMacLearned(learnedMac: String, expectedPort: UUID) : Unit = {
        val ethPkt = { eth src port2OnHost1Mac dst learnedMac } <<
            { ip4 src "10.10.10.10" dst "10.11.11.11" } <<
            { udp src 10 dst 12 } << payload("Test UDP packet")
        ethPkt.setVlanIDs(networkVlans)

        val bridgeDevice: Bridge = fetchDevice[Bridge](bridge)
        val (pktCtx, action) = simulateDevice(bridgeDevice,
                                              ethPkt, port2OnHost1)
        action should be (AddVirtualWildcardFlow)
        pktCtx.virtualFlowActions should have size (1)
        pktCtx.virtualFlowActions.head should be (ToPortAction(expectedPort))
    }

    private def verifyFloodAction(action: SimulationResult, ctx: PacketContext) : Unit = {
        val actualPorts: Set[UUID] = ctx.virtualFlowActions filter
            (_.isInstanceOf[ToPortAction]) map
            (_.asInstanceOf[ToPortAction].outPort) toSet

        val br: Bridge = fetchDevice[Bridge](bridge)
        val expectedPorts: Set[UUID] = br.exteriorPorts filter (_ != ctx.inPortId) toSet

        action should be (AddVirtualWildcardFlow)
        actualPorts should be (expectedPorts)
    }

    private def verifyGeneratedArp(action: SimulationResult, ctx: PacketContext,
                                   port: UUID, srcMac: MAC, dstMac: MAC,
                                   srcIp: IPv4Addr, dstIp: IPv4Addr): Unit = {
        val generatedPacket = ctx.backChannel.find[GeneratedLogicalPacket]()
        generatedPacket.egressPort shouldBe port
        generatedPacket.eth.getSourceMACAddress shouldBe srcMac
        generatedPacket.eth.getDestinationMACAddress shouldBe dstMac

        val generatedArp = generatedPacket.eth.getPayload.asInstanceOf[ARP]
        generatedArp.getSenderHardwareAddress shouldBe srcMac
        generatedArp.getTargetHardwareAddress shouldBe dstMac
        generatedArp.getSenderProtocolAddress shouldBe srcIp.toBytes
        generatedArp.getTargetProtocolAddress shouldBe dstIp.toBytes

        action shouldBe NoOp
    }

    private def verifyDSCP(ctx: PacketContext, dscpMark: Int,
                           requireSetKeyAction: Boolean=true) : Unit = {
        ctx.calculateActionsFromMatchDiff()

        ctx.wcmatch.isSeen(FlowMatch.Field.NetworkTOS) shouldBe true
        ctx.wcmatch.getNetworkTOS shouldBe dscpMark

        if (requireSetKeyAction) {
            val flowActions: Set[FlowKey] = ctx.virtualFlowActions filter
                (_.isInstanceOf[FlowActionSetKey]) filter
                (_.asInstanceOf[FlowActionSetKey].getFlowKey.isInstanceOf[FlowKeyIPv4]) map
                (_.asInstanceOf[FlowActionSetKey].getFlowKey) toSet

            flowActions should not be empty

            val tosActions = flowActions filter
                (_.asInstanceOf[FlowKeyIPv4].ipv4_tos == dscpMark.toByte)

            tosActions should not be empty
        }
    }

    private def verifyNoDSCP(ctx: PacketContext) : Unit = {
        ctx.calculateActionsFromMatchDiff()
        ctx.wcmatch.isSeen(FlowMatch.Field.NetworkTOS) shouldBe false
        ctx.virtualFlowActions filter
            (_.isInstanceOf[FlowActionSetKey]) filter
            (_.asInstanceOf[FlowActionSetKey].getFlowKey.isInstanceOf[FlowKeyIPv4]) map
            (_.asInstanceOf[FlowActionSetKey].getFlowKey) shouldBe empty
    }
}

/**
  * The same tests as the parent
  * [[org.midonet.midolman.BridgeSimulationTest]], but transmitting frames
  * that have one vlan id.
  *
  * The tests are expected to work in exactly the same way, since here we're
  * adding a Vlan Unaware Bridge (all its interior ports are not vlan-tagged)
  * and therefore it should behave with vlan traffic in the same way as if it
  * was not tagged.
  */
class BridgeSimulationTestWithOneVlan extends BridgeSimulationTest {
    override def networkVlans: List[JShort] = List(2.toShort)
}

/**
  * The same tests [[org.midonet.midolman.BridgeSimulationTest]], but
  * transmitting frames that have one vlan id.
  */
class BridgeSimulationTestWithManyVlans extends BridgeSimulationTest {
    override def networkVlans: List[JShort] = List(3,4,5,6) map {
        x => short2Short(x.toShort)
    }
}
