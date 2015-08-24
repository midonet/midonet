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

import java.util.UUID

import scala.collection.JavaConverters._
import scala.collection.JavaConversions._

import com.typesafe.config.{ConfigValueFactory, Config}
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import org.midonet.midolman.PacketWorkflow.{SimulationResult, AddVirtualWildcardFlow, ErrorDrop}
import org.midonet.midolman.simulation.{PacketContext, Bridge, Router}
import org.midonet.midolman.simulation.Simulator.ToPortAction
import org.midonet.midolman.topology.BridgeManager.CheckExpiredMacPorts
import org.midonet.midolman.topology.VirtualTopologyActor
import org.midonet.midolman.util.MidolmanSpec
import org.midonet.midolman.util.mock.MessageAccumulator
import org.midonet.packets.{IPv4Subnet, MAC}
import org.midonet.sdn.flows.FlowTagger
import org.midonet.util.MidonetEventually

@RunWith(classOf[JUnitRunner])
class BridgeInvalidationTest extends MidolmanSpec
                             with MidonetEventually {

    val leftMac = "02:02:01:10:10:aa"
    val leftIp = "192.168.1.1"

    val rightMac = "02:02:02:20:20:aa"
    val rightIp = "192.168.1.2"

    val routerMac = "02:02:03:03:04:04"
    val routerIp = new IPv4Subnet("192.168.1.0", 24)

    var leftPort: UUID = null
    var rightPort: UUID = null
    var otherPort: UUID = null
    var interiorPort: UUID = null
    var routerPort: UUID = null

    var clusterBridge: UUID = null
    var clusterRouter: UUID = null

    val macPortExpiration = 1000

    private def addAndMaterializeBridgePort(br: UUID, iface: String): UUID = {
        val port = newBridgePort(br)
        port should not be null
        materializePort(port, hostId, iface)
        port
    }

    private def buildTopology() {
        val host = newHost("myself", hostId)
        host should not be null

        clusterBridge = newBridge("bridge")
        clusterBridge should not be null
        clusterRouter = newRouter("router")
        clusterRouter should not be null

        leftPort = addAndMaterializeBridgePort(clusterBridge, "left")
        rightPort = addAndMaterializeBridgePort(clusterBridge, "right")
        otherPort = addAndMaterializeBridgePort(clusterBridge, "other")

        interiorPort = newBridgePort(clusterBridge)
        routerPort = newRouterPort(clusterRouter, MAC.fromString(routerMac), routerIp)

        fetchPorts(leftPort, rightPort, otherPort, interiorPort, routerPort)
        fetchDevice[Bridge](clusterBridge)
        fetchDevice[Router](clusterRouter)
        flowInvalidator.clear()
    }

    override protected def fillConfig(config: Config) = {
        super.fillConfig(config).withValue(
            "agent.bridge.mac_port_mapping_expire",
            ConfigValueFactory.fromAnyRef(s"${macPortExpiration}ms"))
    }

    registerActors(VirtualTopologyActor -> (() => new VirtualTopologyActor()
                                                  with MessageAccumulator))

    override def beforeTest() {
        buildTopology()
    }

    def leftToRightFrame = {
        import org.midonet.packets.util.PacketBuilder._

        { eth addr leftMac -> rightMac } <<
            { ip4 addr leftIp --> rightIp } <<
                { udp ports 53 ---> 53 } <<
                    payload(UUID.randomUUID().toString)
    }

    def leftToRouterFrame = {
        import org.midonet.packets.util.PacketBuilder._

        { eth addr leftMac -> routerMac } <<
            { ip4 addr leftIp --> routerIp.toUnicastString } <<
                { udp ports 53 ---> 53 } <<
                    payload(UUID.randomUUID().toString)
    }

    def leftToRouterArpFrame = {
        import org.midonet.packets.util.PacketBuilder._

        { eth addr leftMac -> eth_bcast } <<
            { arp.req mac leftMac -> eth_bcast ip leftIp --> routerIp.toUnicastString }
    }

    def leftPortUnicastInvalidation =
        FlowTagger.tagForVlanPort(clusterBridge,
            MAC.fromString(leftMac), 0.toShort, leftPort)

    def rightPortUnicastInvalidation =
        FlowTagger.tagForVlanPort(clusterBridge,
            MAC.fromString(rightMac), 0.toShort, rightPort)

    def otherPortUnicastInvalidation =
        FlowTagger.tagForVlanPort(clusterBridge,
            MAC.fromString(rightMac), 0.toShort, otherPort)

    def leftMacFloodInvalidation =
        FlowTagger.tagForFloodedFlowsByDstMac(clusterBridge,
            Bridge.UntaggedVlanId, MAC.fromString(leftMac))

    def rightMacFloodInvalidation =
        FlowTagger.tagForFloodedFlowsByDstMac(clusterBridge,
            Bridge.UntaggedVlanId, MAC.fromString(rightMac))

    def floodInvalidation = FlowTagger.tagForBroadcast(clusterBridge)

    def routerMacFloodInvalidation =
        FlowTagger.tagForFloodedFlowsByDstMac(clusterBridge,
            Bridge.UntaggedVlanId, MAC.fromString(routerMac))

    private def verifyFloodAction(br: Bridge, action: SimulationResult, ctx: PacketContext) : Unit = {
        val actualPorts: Set[UUID] = ctx.virtualFlowActions filter
            (_.isInstanceOf[ToPortAction]) map
            (_.asInstanceOf[ToPortAction].outPort) toSet

        val expectedPorts: Set[UUID] = br.exteriorPorts filter (_ != ctx.inPortId) toSet

        action should be (AddVirtualWildcardFlow)
        actualPorts should be (expectedPorts)
    }

    feature("Bridge invalidates flows when a MAC is learned") {
        scenario("flooded flows are properly tagged") {
            When("a packet is sent across the bridge between two VMs")
            val bridge: Bridge = fetchDevice[Bridge](clusterBridge)
            val (pktContext, action) = simulateDevice(bridge, leftToRightFrame, leftPort)

            Then("the bridge floods the packet to all exterior ports")
            verifyFloodAction(bridge, action, pktContext)

            And("The flow is tagged with flood, ingress port, and device tags")
            val expectedTags = Seq(
                FlowTagger.tagForBridge(bridge.id),
                leftPortUnicastInvalidation,
                rightMacFloodInvalidation,
                floodInvalidation)
            pktContext should be (taggedWith(expectedTags :_*))
        }

        scenario("unicast flows are properly tagged") {
            When("A bridge learns a MAC address")
            val bridge: Bridge = fetchDevice[Bridge](clusterBridge)
            val macTable = bridge.vlanMacTableMap(0.toShort)
            macTable.add(MAC.fromString(rightMac), rightPort)

            And("A packet is sent across the bridge to that MAC address")
            val (pktContext, action) = simulateDevice(bridge, leftToRightFrame, leftPort)

            Then("A unicast flow is created")
            pktContext.virtualFlowActions.head should be (ToPortAction(rightPort))
            action should be (AddVirtualWildcardFlow)

            And("The flow is tagged with ingress port, egress port and device tags")
            val expectedTags = Seq(
                FlowTagger.tagForBridge(bridge.id),
                leftPortUnicastInvalidation,
                rightPortUnicastInvalidation)
            pktContext should be (taggedWith(expectedTags :_*))
        }

        scenario("VM migration") {
            When("A bridge learns a MAC address for the first time")
            val bridge: Bridge = fetchDevice[Bridge](clusterBridge)
            val macTable = bridge.vlanMacTableMap(0.toShort)
            macTable.add(MAC.fromString(rightMac), rightPort)

            Then("A flow invalidation for the flooded case should be produced")
            flowInvalidator should invalidate(rightMacFloodInvalidation)

            When("A MAC address migrates across two ports")
            macTable.add(MAC.fromString(rightMac), otherPort)

            Then("A flow invalidation for the unicast case should be produced")
            flowInvalidator should invalidate(rightMacFloodInvalidation)

            And("new packets should be directed to the newly associated port")
            val (pktContext, action) = simulateDevice(bridge, leftToRightFrame, leftPort)
            val expectedTags = Seq(
                FlowTagger.tagForBridge(bridge.id),
                leftPortUnicastInvalidation,
                otherPortUnicastInvalidation)

            pktContext.virtualFlowActions.head should be (ToPortAction(otherPort))
            pktContext should be (taggedWith(expectedTags :_*))
            action should be (AddVirtualWildcardFlow)
        }

        scenario("Packet whose dst mac resolves to the inPort is dropped") {
            When("A bridge learns a MAC address")
            val bridge: Bridge = fetchDevice[Bridge](clusterBridge)
            val macTable = bridge.vlanMacTableMap(0.toShort)
            macTable.add(MAC.fromString(rightMac), leftPort)

            Then("A flow invalidation for the flooded case should be produced")
            flowInvalidator should invalidate(rightMacFloodInvalidation)

            And("If a packet with the same dst mac comes from that port")
            val (_, action) = simulateDevice(bridge, leftToRightFrame, leftPort)
            action should be (ErrorDrop)
        }
    }

    feature("MAC-port mapping expiration") {
        scenario("A MAC-port mapping expires when its flows are removed") {
            When("a packet is sent across the bridge between two VMs")
            val bridge: Bridge = fetchDevice[Bridge](clusterBridge)
            val (pktContext, action) = simulateDevice(bridge, leftToRightFrame, leftPort)
            flowInvalidator.clear()

            And("The corresponding flow expires")
            pktContext.flowRemovedCallbacks.runAndClear()

            Then("The MAC port mapping expires too")
            Thread.sleep(macPortExpiration)
            // Since the check for the expiration of the MAC port association is
            // done every 2 seconds, let's trigger it
            val bridgeManagerPath =
                VirtualTopologyActor.path + "/BridgeManager-" + bridge.id.toString
            val bridgeManager = actorSystem.actorSelection(bridgeManagerPath)

            And("A flow invalidation is produced")
            eventually {
                bridgeManager ! CheckExpiredMacPorts()
                flowInvalidator should invalidate(leftPortUnicastInvalidation)
            }
        }
    }

    feature("Flow invalidations related to interior bridge ports ") {
        scenario("a interior port is linked") {
            When("A VM ARPs for the router's IP address while the router is not connected")
            val bridge: Bridge = fetchDevice[Bridge](clusterBridge)
            val (pktContext, action) =
                simulateDevice(bridge, leftToRouterArpFrame, leftPort)

            Then("The bridge should flood the packet")
            verifyFloodAction(bridge, action, pktContext)

            When("The router is linked to the bridge")
            linkPorts(routerPort, leftPort)

            Then("Invalidations for flooded and unicast flows should happen")
            flowInvalidator should invalidate(
                routerMacFloodInvalidation, FlowTagger.tagForArpRequests(bridge.id))
        }

        scenario("a interior port is unlinked") {
            When("The router is linked to the bridge")
            var bridge: Bridge = fetchDevice[Bridge](clusterBridge)
            linkPorts(routerPort, interiorPort)
            eventually {
                val newBridge: Bridge = fetchDevice[Bridge](clusterBridge)
                newBridge should not be bridge
            }
            bridge = fetchDevice[Bridge](clusterBridge)
            flowInvalidator.clear()

            val interiorPortTag =
                FlowTagger.tagForBridgePort(bridge.id, interiorPort)
            val routerPortTag =
                FlowTagger.tagForPort(routerPort)

            And("The packet makes to to the router")
            val (pktContext, action) = simulateDevice(bridge, leftToRouterFrame, leftPort)
            pktContext.virtualFlowActions should have size (0)
            pktContext should be (taggedWith(interiorPortTag, routerPortTag))
            flowInvalidator.clear()

            And("The interior port is then unlinked")
            unlinkPorts(interiorPort)

            Then("A flow invalidation should be produced")
            flowInvalidator should invalidate(interiorPortTag)
        }
    }
}
