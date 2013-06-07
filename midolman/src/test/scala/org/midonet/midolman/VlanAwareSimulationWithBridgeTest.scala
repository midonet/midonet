package org.midonet.midolman

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.apache.commons.configuration.HierarchicalConfiguration
import org.midonet.cluster.data.Bridge
import org.junit.Test
import org.midonet.packets.{Packets, IPv4Addr}
import java.nio.ByteBuffer
import org.midonet.odp.flows.FlowActionOutput

@RunWith(classOf[JUnitRunner])
class VlanAwareSimulationWithBridgeTest extends MidolmanTestCase
                            with VlanBridgeSimulationTestCase {

    type T = Bridge
    override var vlanBridge: T = null

    override def hasMacLearning: Boolean = true

    // VAB-specific ports, device
    override protected def fillConfig(config: HierarchicalConfiguration) = {
        config.setProperty("datapath.max_flow_count", "10")
        super.fillConfig(config)
    }

    override def beforeTest() {
        vlanBridge = newBridge("vlan-bridge")
        val trunkPort1 = newExteriorBridgePort(vlanBridge)
        val trunkPort2 = newExteriorBridgePort(vlanBridge)
        trunk1Id = trunkPort1.getId
        trunk2Id = trunkPort2.getId
        val intVlanPort1 = newInteriorBridgePort(vlanBridge, Some(vlanId1))
        val intVlanPort2 = newInteriorBridgePort(vlanBridge, Some(vlanId2))
        intVlanPort1Id = intVlanPort1.getId
        intVlanPort2Id = intVlanPort2.getId

        buildCommonTopology()

        clusterDataClient().portsLink(intVlanPort1Id, br1IntPort.getId)
        clusterDataClient().portsLink(intVlanPort2Id, br2IntPort.getId)

        initialize()
    }

    /**
     * Tests that the vlan-bridge receives a frame in a trunk with a vlan id
     * that it doesn't know.
     *
     * When the VAB is implemented by the Bridge, it'll chose to broadcast to
     * all the other trunk ports. In this case, we send an ARP from a trunk,
     * with a vlan-id that the bridge will not recognize. It should just send
     * the frame to the other trunks (in this scenario, one).
     */
    @Test
    def testFrameFromTrunkWithUnknownVlanId() {
        feedBridgeArpCaches()
        val eth = Packets.arpRequest(trunkMac, trunkIp.toIntIPv4,
            IPv4Addr.fromString("10.1.1.22").toIntIPv4)
        eth.deserialize(ByteBuffer.wrap(eth.serialize()))
        eth.setVlanID(500)
        injectOnePacket(eth, trunk1Id)
        val msg = packetEventsProbe.expectMsgClass(classOf[PacketsExecute])
        msg.packet.getActions.size() should be === 1
        msg.packet.getActions.get(0) match {
            case act: FlowActionOutput =>
                act.getPortNumber should be === getPortNumber("trunkPort2")
        }
    }

}
