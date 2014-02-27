/*
 * Copyright (c) 2014 Midokura Europe SARL, All Rights Reserved.
 */

package org.midonet.midolman.simulation

import scala.concurrent.Await
import scala.concurrent.duration._

import akka.util.Timeout

import org.junit.runner.RunWith
import org.scalatest.{OneInstancePerTest, GivenWhenThen, Matchers, FeatureSpec}
import org.scalatest.junit.JUnitRunner

import org.midonet.cache.MockCache
import org.midonet.cluster.data.{Bridge => ClusterBridge}
import org.midonet.cluster.data.ports.BridgePort
import org.midonet.midolman._
import org.midonet.midolman.DeduplicationActor.EmitGeneratedPacket
import org.midonet.midolman.PacketWorkflow.{TemporaryDrop, SimulationResult}
import org.midonet.midolman.rules.RuleResult
import org.midonet.midolman.services.MessageAccumulator
import org.midonet.midolman.topology.{FlowTagger, VirtualTopologyActor}
import org.midonet.odp.flows.IPFragmentType
import org.midonet.packets._
import org.midonet.packets.ICMP.UNREACH_CODE
import org.midonet.packets.util.PacketBuilder._
import org.midonet.sdn.flows.WildcardMatch

@RunWith(classOf[JUnitRunner])
class IPFragmentationTest extends FeatureSpec
                          with Matchers
                          with GivenWhenThen
                          with CustomMatchers
                          with MockMidolmanActors
                          with VirtualConfigurationBuilders
                          with MidolmanServices
                          with VirtualTopologyHelper
                          with OneInstancePerTest {

    implicit val askTimeout: Timeout = 1 second

    override def registerActors = List(
        VirtualTopologyActor -> (() => new VirtualTopologyActor
                                       with MessageAccumulator))

    /*
     * The topology for this test consists of a single bridge with 2 ports.
     */

    val ipLeftSide = new IPv4Subnet("10.0.0.64", 24)
    val macLeftSide = MAC.random
    val ipRightSide = new IPv4Subnet("10.0.0.65", 24)
    val macRightSide = MAC.random

    var bridge: ClusterBridge = _
    var leftBridgePort: BridgePort = _
    var rightBridgePort: BridgePort = _

    override def beforeTest() {
        newHost("myself", hostId)
        bridge = newBridge("bridge0")

        leftBridgePort = newBridgePort(bridge)
        rightBridgePort = newBridgePort(bridge)

        materializePort(leftBridgePort, hostId, "port0")
        materializePort(rightBridgePort, hostId, "port1")

        val topo = fetchTopology(bridge, leftBridgePort, rightBridgePort)

        topo.collect({ case b: Bridge => b.vlanMacTableMap})
                .head(ClusterBridge.UNTAGGED_VLAN_ID)
                .add(macRightSide, rightBridgePort.getId)
    }

    feature("Fragmentation is ignored if no L4 fields are touched") {
        for (fragType <- IPFragmentType.values()) {
            scenario(s"a $fragType packet is allowed through") {
                Given("an IP packet with IP_FLAGS_MF set")

                When("it is simulated")

                val simRes = sendPacket(IPFragmentType.None)

                Then("a to port flow action is emitted")

                simRes should be (toPort(rightBridgePort.getId) {
                    FlowTagger.invalidateFlowsByDevice(leftBridgePort.getId)
                    FlowTagger.invalidateFlowsByDevice(bridge.getId)
                    FlowTagger.invalidateFlowsByDevice(rightBridgePort.getId)
                })
            }
        }
    }

    feature("Packet fragmentation is not supported") {
        scenario("an unfragmented packet is allowed through") {
            Given("an IP packet with IP_FLAGS_MF set")

            setupL4TouchingChain()

            When("it is simulated")

            val simRes = sendPacket(IPFragmentType.None)

            Then("a to port flow action is emitted")

            simRes should be (toPort(rightBridgePort.getId) {
                FlowTagger.invalidateFlowsByDevice(leftBridgePort.getId)
                FlowTagger.invalidateFlowsByDevice(bridge.getId)
                FlowTagger.invalidateFlowsByDevice(rightBridgePort.getId)
            })
        }

        scenario("a first packet is replied to with a frag. needed ICMP") {
            Given("an IP packet with IP_FLAGS_MF set")

            setupL4TouchingChain()

            When("it is simulated")

            val drop@TemporaryDrop(tags, _) = sendPacket(IPFragmentType.First)

            Then("a temporary drop flow is installed")

            drop should be (dropped())
            tags should be (empty)

            And("a fragmentation needed ICMP should be received")

            DeduplicationActor.messages should not be empty
            val msg = DeduplicationActor.messages.head.asInstanceOf[EmitGeneratedPacket]
            msg should not be null

            msg.egressPort should be(leftBridgePort.getId)

            val ipPkt = msg.eth.getPayload.asInstanceOf[IPv4]
            ipPkt should not be null

            ipPkt.getProtocol should be(ICMP.PROTOCOL_NUMBER)

            val icmpPkt = ipPkt.getPayload.asInstanceOf[ICMP]
            icmpPkt should not be null

            icmpPkt.getType should be (ICMP.TYPE_UNREACH)
            icmpPkt.getCode should be (UNREACH_CODE.UNREACH_FRAG_NEEDED.toByte)
        }

        scenario("a first packet with an unsupported ethertype is dropped") {
            Given("an IP packet with IP_FLAGS_MF set")

            setupL4TouchingChain()

            When("it is simulated")

            val drop@TemporaryDrop(tags, _) = sendPacket(IPFragmentType.First,
                                                         IPv6.ETHERTYPE)
            Then("a temporary drop flow is installed")

            drop should be (dropped())
            tags should be (empty)
        }

        scenario("a later packet is dropped") {
            Given("an IP packet with IP_FLAGS_MF set")

            setupL4TouchingChain()

            When("it is simulated")

            val simRes = sendPacket(IPFragmentType.Later)

            Then("a drop flow is installed")

            simRes should be (dropped {
                FlowTagger.invalidateFlowsByDevice(leftBridgePort.getId)
                FlowTagger.invalidateFlowsByDevice(bridge.getId)
                FlowTagger.invalidateFlowsByDevice(rightBridgePort.getId)
            })
        }
    }

    private[this] def setupL4TouchingChain() {
        val chain = newInboundChainOnBridge("brInFilter", bridge)
        newTcpDstRuleOnChain(chain, 1, 80, RuleResult.Action.ACCEPT)
        fetchTopology(chain)
    }

    private[this] def sendPacket(fragType: IPFragmentType,
                                 etherType: Short = IPv4.ETHERTYPE)
    : SimulationResult = {
        val pkt = makePacket(fragType, etherType)
        Await.result(new Coordinator(
            makeWMatch(pkt),
            pkt,
            Some(1),
            None,
            0,
            new MockCache(),
            new MockCache(),
            new MockCache(),
            None,
            Nil).simulate(), Duration.Inf)
    }

    private[this] def makePacket(fragType: IPFragmentType, etherType: Short) = {
        val flags = if (fragType == IPFragmentType.None) 0 else IPv4.IP_FLAGS_MF
        val offset = if (fragType == IPFragmentType.Later) 0x4321 else 0

        eth.src(macLeftSide).dst(macRightSide).ether_type(etherType) <<
            ip4.src(ipLeftSide.toUnicastString).dst(ipRightSide.toUnicastString)
               .flags(flags.toByte).frag_offset(offset.toShort) <<
                    tcp.src(81).dst(81)
    }

    private[this] def makeWMatch(pkt: Ethernet) =
        WildcardMatch.fromEthernetPacket(pkt)
                     .setInputPortUUID(leftBridgePort.getId)
}
