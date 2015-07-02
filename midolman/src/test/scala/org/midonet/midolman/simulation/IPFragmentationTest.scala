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

import java.util.{LinkedList, UUID}

import scala.concurrent.duration._

import akka.util.Timeout
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import org.midonet.cluster.data.{Entity, Port}
import org.midonet.midolman.PacketWorkflow.{SimulationResult, ErrorDrop}
import org.midonet.midolman.rules.FragmentPolicy
import org.midonet.midolman.rules.FragmentPolicy._
import org.midonet.midolman.rules.RuleResult.Action
import org.midonet.midolman.simulation.PacketEmitter.GeneratedPacket
import org.midonet.midolman.topology.VirtualTopologyActor
import org.midonet.midolman.util.MidolmanSpec
import org.midonet.midolman.util.mock.MessageAccumulator
import org.midonet.packets.ICMP.UNREACH_CODE
import org.midonet.packets._
import org.midonet.packets.util.PacketBuilder._
import org.midonet.sdn.flows.FlowTagger

@RunWith(classOf[JUnitRunner])
class IPFragmentationTest extends MidolmanSpec {
    implicit val askTimeout: Timeout = 1 second

    registerActors(VirtualTopologyActor -> (() => new VirtualTopologyActor
                                                  with MessageAccumulator))

    /*
     * The topology for this test consists of a single bridge or
     * router with 2 ports. Which one depends on the useRouter
     * value passed to setup().
     */

    var bridge: UUID = _

    val leftBridgePortMac = MAC.random
    val leftBridgePortSubnet = new IPv4Subnet("10.0.0.64", 24)

    val rightBridgePortMac = MAC.random
    val rightBridgePortSubnet = new IPv4Subnet("10.0.0.65", 24)

    var router: UUID = _

    val leftRouterPortMac = MAC.random
    val leftRouterPortSubnet = new IPv4Subnet("10.10.11.10", 24)

    val rightRouterPortMac = MAC.random
    val rightRouterPortSubnet = new IPv4Subnet("10.10.12.10", 24)

    // These refer to either bridge or router versions, depending
    // on the value of useRouter passed to setup().
    var useRouter: Boolean = false
    var deviceId: UUID = _
    var srcPort: Port[_, _] = _
    var dstPort: Port[_, _] = _
    var srcMac: MAC = _
    var dstMac: MAC = _
    var srcSubnet: IPv4Subnet = _
    var dstSubnet: IPv4Subnet = _

    val packetEmitter = new LinkedList[GeneratedPacket]

    def setup(useRouter: Boolean = false) {
        this.useRouter = useRouter
        newHost("myself", hostId)

        if (useRouter) {
            router = newRouter("router1")
            deviceId = router

            srcPort = newRouterPort(router, leftRouterPortMac,
                                    leftRouterPortSubnet)
            srcMac = leftRouterPortMac
            srcSubnet = leftRouterPortSubnet

            dstPort = newRouterPort(router, rightRouterPortMac,
                                    rightRouterPortSubnet)
            dstMac = leftRouterPortMac
            dstSubnet = rightRouterPortSubnet
        } else {
            bridge = newBridge("bridge0")
            deviceId = bridge

            srcPort = newBridgePort(bridge)
            srcMac = leftBridgePortMac
            srcSubnet = leftBridgePortSubnet

            dstPort = newBridgePort(bridge)
            dstMac = rightBridgePortMac
            dstSubnet = rightBridgePortSubnet

            materializePort(srcPort, hostId, "bport0")
            materializePort(dstPort, hostId, "bport1")

            val topo = fetchTopology(srcPort, dstPort)
            val simBridge = fetchDevice[Bridge](bridge)
            feedMacTable(simBridge, dstMac, dstPort.getId)
        }
    }

    feature("Rules are applied or not according to fragment fragmentPolicy") {
        val policyMatrix = Map((ANY, IPFragmentType.First) -> true,
                               (ANY, IPFragmentType.Later) -> true,
                               (ANY, IPFragmentType.None) -> true,
                               (UNFRAGMENTED, IPFragmentType.First) -> false,
                               (UNFRAGMENTED, IPFragmentType.Later) -> false,
                               (UNFRAGMENTED, IPFragmentType.None) -> true,
                               (HEADER, IPFragmentType.First) -> true,
                               (HEADER, IPFragmentType.Later) -> false,
                               (HEADER, IPFragmentType.None) -> true,
                               (NONHEADER, IPFragmentType.First) -> false,
                               (NONHEADER, IPFragmentType.Later) -> true,
                               (NONHEADER, IPFragmentType.None) -> false)
        for (((policy, fragType), accepted) <- policyMatrix) {
            scenario(s"$policy accepts fragment type $fragType: $accepted") {
                Given(s"A chain with an accept rule with $policy fragmentPolicy")
                setup()
                setupAcceptOrDropChain(policy, ANY)

                When(s"A packet with fragment type $fragType is sent")
                val simRes = send(fragType)

                if (accepted) {
                    Then("A to port flow action is emitted.")
                    assertToPortFlowCreated(simRes)
                } else {
                    Then("A drop flow action is emitted")
                    assertDropFlowCreated(simRes)
                }
            }
        }
    }

    feature("Respond to header fragments dropped by a router with ICMP_FRAG_NEEDED") {
        scenario("Fragments dropped by bridge do not prompt ICMP_FRAG_NEEDED") {
            Given("A bridge with a chain which drops fragmented packets")
            setup()
            setupAcceptOrDropChain(UNFRAGMENTED, ANY)

            When("A header fragment is sent")
            val simRes = send(IPFragmentType.First)

            Then("A drop flow should be installed")
            assertDropFlowCreated(simRes, temporary = false)

            And("No ICMP_FRAG_NEEDED message should be received.")
            packetEmitter should be (empty)
        }

        scenario("Header fragment dropped by router prompts ICMP_FRAG_NEEDED") {
            Given("A router wich a chain which drops fragmented packets")
            setup(useRouter = true)
            setupAcceptOrDropChain(UNFRAGMENTED, ANY)

            When("A header fragment is sent")
            val simRes = send(IPFragmentType.First)

            Then("A temporary drop flow should be installed")
            assertDropFlowCreated(simRes, temporary = true)

            And("An ICMP_FRAG_NEEDED message should be received.")
            assertIcmpFragNeededMessageReceived(simRes._2)
        }

        scenario("Nonheader fragments silently dropped by router") {
            Given("A router wich a chain which drops fragmented packets")
            setup(useRouter = true)
            setupAcceptOrDropChain(UNFRAGMENTED, ANY)

            When("A nonheader fragment is sent")
            val simRes = send(IPFragmentType.Later)

            Then("A drop flow should be installed")
            assertDropFlowCreated(simRes, temporary = false)

            And("No ICMP_FRAG_NEEDED message should be received.")
            packetEmitter should be (empty)
        }
    }

    private[this] def setupAcceptOrDropChain(acceptPolicy: FragmentPolicy,
                                             dropPolicy: FragmentPolicy) {
        val chain = if (useRouter) newInboundChainOnRouter("rtInFilter", router)
                    else newInboundChainOnBridge("brInFilter", bridge)
        newFragmentRuleOnChain(chain, 1, acceptPolicy, Action.ACCEPT)
        newFragmentRuleOnChain(chain, 2, dropPolicy, Action.DROP)
    }

    private[this] def send(fragType: IPFragmentType,
                           etherType: Short = IPv4.ETHERTYPE)
    : (SimulationResult, PacketContext) = {
        val pkt = makePacket(fragType, etherType)
        val pktCtx = packetContextFor(pkt, srcPort.getId, packetEmitter)
        simulate(pktCtx)
    }

    private[this] def makePacket(fragType: IPFragmentType, etherType: Short) = {
        val flags = if (fragType == IPFragmentType.None) 0 else IPv4.IP_FLAGS_MF
        val offset = if (fragType == IPFragmentType.Later) 0x4321 else 0

        val builder = eth.src(srcMac).dst(dstMac) <<
            ip4.src(srcSubnet.toUnicastString).dst(dstSubnet.toUnicastString)
               .flags(flags.toByte).frag_offset(offset.toShort) <<
                    tcp.src(81).dst(81)

        // Have to set ethertype here, after the ethertype gets
        // overwritten by the L3 payload's ethertype.
        builder.ether_type(etherType).packet
    }

    private def assertToPortFlowCreated(simRes: (SimulationResult, PacketContext)) {
        simRes should be (toPort(dstPort.getId)
            (FlowTagger.tagForDevice(srcPort.getId),
             FlowTagger.tagForDevice(deviceId),
             FlowTagger.tagForDevice(dstPort.getId)))
    }

    private def assertDropFlowCreated(simRes: (SimulationResult, PacketContext),
                                      temporary: Boolean = false) {
        if (temporary) {
            simRes._1 shouldBe ErrorDrop
            return
        }

        simRes shouldBe dropped(
            FlowTagger.tagForDevice(srcPort.getId),
            FlowTagger.tagForDevice(deviceId))
    }

    private def assertIcmpFragNeededMessageReceived(context: PacketContext) {
        context.packetEmitter.pendingPackets should be (1)
        val generatedPacket = context.packetEmitter.poll()

        generatedPacket.egressPort should be(srcPort.getId)

        val ipPkt = generatedPacket.eth.getPayload.asInstanceOf[IPv4]
        ipPkt should not be null

        ipPkt.getProtocol should be(ICMP.PROTOCOL_NUMBER)

        val icmpPkt = ipPkt.getPayload.asInstanceOf[ICMP]
        icmpPkt should not be null

        icmpPkt.getType should be (ICMP.TYPE_UNREACH)
        icmpPkt.getCode should be (UNREACH_CODE.UNREACH_FRAG_NEEDED.toByte)
    }
}
