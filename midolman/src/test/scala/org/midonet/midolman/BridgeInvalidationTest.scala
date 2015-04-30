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

import com.typesafe.config.{ConfigValueFactory, Config}
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import org.midonet.cluster.data.ports.{BridgePort, RouterPort}
import org.midonet.cluster.data.{Bridge => ClusterBridge, Router => ClusterRouter}
import org.midonet.midolman.PacketWorkflow.ErrorDrop
import org.midonet.midolman.simulation.Bridge
import org.midonet.midolman.simulation.Coordinator.ToPortAction
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

    var leftPort: BridgePort = null
    var rightPort: BridgePort = null
    var otherPort: BridgePort = null
    var interiorPort: BridgePort = null
    var routerPort: RouterPort = null

    var clusterBridge: ClusterBridge = null
    var clusterRouter: ClusterRouter = null

    val macPortExpiration = 1000

    private def addAndMaterializeBridgePort(br: ClusterBridge): BridgePort = {
        val port = newBridgePort(br)
        port should not be null
        stateStorage.setPortLocalAndActive(port.getId, hostId, true)
        port
    }

    private def buildTopology() {
        val host = newHost("myself", hostId)
        host should not be null

        clusterBridge = newBridge("bridge")
        clusterBridge should not be null
        clusterRouter = newRouter("router")
        clusterRouter should not be null

        leftPort = addAndMaterializeBridgePort(clusterBridge)
        rightPort = addAndMaterializeBridgePort(clusterBridge)
        otherPort = addAndMaterializeBridgePort(clusterBridge)

        interiorPort = newBridgePort(clusterBridge)
        routerPort = newRouterPort(clusterRouter, MAC.fromString(routerMac), routerIp)

        fetchTopology(leftPort, rightPort, otherPort, interiorPort,
                        routerPort, clusterRouter, clusterBridge)

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
        FlowTagger.tagForVlanPort(clusterBridge.getId,
            MAC.fromString(leftMac), 0.toShort, leftPort.getId)

    def rightPortUnicastInvalidation =
        FlowTagger.tagForVlanPort(clusterBridge.getId,
            MAC.fromString(rightMac), 0.toShort, rightPort.getId)

    def otherPortUnicastInvalidation =
        FlowTagger.tagForVlanPort(clusterBridge.getId,
            MAC.fromString(rightMac), 0.toShort, otherPort.getId)

    def leftMacFloodInvalidation =
        FlowTagger.tagForFloodedFlowsByDstMac(clusterBridge.getId,
            ClusterBridge.UNTAGGED_VLAN_ID, MAC.fromString(leftMac))

    def rightMacFloodInvalidation =
        FlowTagger.tagForFloodedFlowsByDstMac(clusterBridge.getId,
            ClusterBridge.UNTAGGED_VLAN_ID, MAC.fromString(rightMac))

    def floodInvalidation = FlowTagger.tagForBroadcast(clusterBridge.getId)

    def routerMacFloodInvalidation =
        FlowTagger.tagForFloodedFlowsByDstMac(clusterBridge.getId,
            ClusterBridge.UNTAGGED_VLAN_ID, MAC.fromString(routerMac))

    feature("Bridge invalidates flows when a MAC is learned") {
        scenario("flooded flows are properly tagged") {
            When("a packet is sent across the bridge between two VMs")
            val bridge: Bridge = fetchDevice(clusterBridge)
            val (pktContext, action) = simulateDevice(bridge, leftToRightFrame, leftPort.getId)

            Then("the bridge floods the packet to all exterior ports")
            action should be (bridge.floodAction)

            And("The flow is tagged with flood, ingress port, and device tags")
            val expectedTags = Set(
                FlowTagger.tagForDevice(bridge.id),
                leftPortUnicastInvalidation,
                rightMacFloodInvalidation,
                floodInvalidation)
            pktContext.flowTags.asScala.toSet should be (expectedTags)
        }

        scenario("unicast flows are properly tagged") {
            When("A bridge learns a MAC address")
            val bridge: Bridge = fetchDevice(clusterBridge)
            val macTable = bridge.vlanMacTableMap(0.toShort)
            macTable.add(MAC.fromString(rightMac), rightPort.getId)

            And("A packet is sent across the bridge to that MAC address")
            val (pktContext, action) = simulateDevice(bridge, leftToRightFrame, leftPort.getId)

            Then("A unicast flow is created")
            action should be (ToPortAction(rightPort.getId))

            And("The flow is tagged with ingress port, egress port and device tags")
            val expectedTags = Set(
                FlowTagger.tagForDevice(bridge.id),
                leftPortUnicastInvalidation,
                rightPortUnicastInvalidation)
            pktContext.flowTags.asScala.toSet should be (expectedTags)
        }

        scenario("VM migration") {
            When("A bridge learns a MAC address for the first time")
            val bridge: Bridge = fetchDevice(clusterBridge)
            val macTable = bridge.vlanMacTableMap(0.toShort)
            macTable.add(MAC.fromString(rightMac), rightPort.getId)

            Then("A flow invalidation for the flooded case should be produced")
            flowInvalidator should invalidate(rightMacFloodInvalidation)

            When("A MAC address migrates across two ports")
            macTable.add(MAC.fromString(rightMac), otherPort.getId)

            Then("A flow invalidation for the unicast case should be produced")
            flowInvalidator should invalidate(rightMacFloodInvalidation)

            And("new packets should be directed to the newly associated port")
            val (pktContext, action) = simulateDevice(bridge, leftToRightFrame, leftPort.getId)
            val expectedTags = Set(
                FlowTagger.tagForDevice(bridge.id),
                leftPortUnicastInvalidation,
                otherPortUnicastInvalidation)

            action should be (ToPortAction(otherPort.getId))
            pktContext.flowTags.asScala.toSet should be (expectedTags)
        }

        scenario("Packet whose dst mac resolves to the inPort is dropped") {
            When("A bridge learns a MAC address")
            val bridge: Bridge = fetchDevice(clusterBridge)
            val macTable = bridge.vlanMacTableMap(0.toShort)
            macTable.add(MAC.fromString(rightMac), leftPort.getId)

            Then("A flow invalidation for the flooded case should be produced")
            flowInvalidator should invalidate(rightMacFloodInvalidation)

            And("If a packet with the same dst mac comes from that port")
            val (_, action) = simulateDevice(bridge, leftToRightFrame, leftPort.getId)
            action should be (ErrorDrop)
        }
    }

    feature("MAC-port mapping expiration") {
        scenario("A MAC-port mapping expires when its flows are removed") {
            When("a packet is sent across the bridge between two VMs")
            val bridge: Bridge = fetchDevice(clusterBridge)
            val (pktContext, action) = simulateDevice(bridge, leftToRightFrame, leftPort.getId)
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
            val bridge: Bridge = fetchDevice(clusterBridge)
            val (pktContext, action) =
                simulateDevice(bridge, leftToRouterArpFrame, leftPort.getId)

            Then("The bridge should flood the packet")
            action should be (bridge.floodAction)

            When("The router is linked to the bridge")
            clusterDataClient.portsLink(routerPort.getId, leftPort.getId)

            Then("Invalidations for flooded and unicast flows should happen")
            flowInvalidator should invalidate(
                routerMacFloodInvalidation, FlowTagger.tagForArpRequests(bridge.id))
        }

        scenario("a interior port is unlinked") {
            When("The router is linked to the bridge")
            var bridge: Bridge = fetchDevice(clusterBridge)
            clusterDataClient.portsLink(routerPort.getId, interiorPort.getId)
            eventually {
                val newBridge: Bridge = fetchDevice(clusterBridge)
                newBridge should not be bridge
            }
            bridge = fetchDevice(clusterBridge)
            flowInvalidator.clear()

            val interiorPortTag =
                FlowTagger.tagForBridgePort(bridge.id, interiorPort.getId)

            And("A flow addressed to the router is installed")
            val (pktContext, action) = simulateDevice(bridge, leftToRouterFrame, leftPort.getId)
            action should be (ToPortAction(interiorPort.getId))
            pktContext.flowTags should contain (interiorPortTag)
            flowInvalidator.clear()

            And("The interior port is then unlinked")
            clusterDataClient.portsUnlink(interiorPort.getId)

            Then("A flow invalidation should be produced")
            flowInvalidator should invalidate(interiorPortTag)
        }
    }
}
