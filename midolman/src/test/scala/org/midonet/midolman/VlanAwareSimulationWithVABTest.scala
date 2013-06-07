package org.midonet.midolman

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.apache.commons.configuration.HierarchicalConfiguration
import org.midonet.cluster.data.VlanAwareBridge
import org.junit.Test
import org.midonet.packets.Packets
import java.nio.ByteBuffer
import org.midonet.midolman.DeduplicationActor.DiscardPacket

@RunWith(classOf[JUnitRunner])
class VlanAwareSimulationWithVABTest extends MidolmanTestCase
with VlanBridgeSimulationTestCase {

    type T = VlanAwareBridge
    override var vlanBridge: T = null

    override def hasMacLearning: Boolean = false

    // VAB-specific ports, device
    override protected def fillConfig(config: HierarchicalConfiguration) = {
        config.setProperty("datapath.max_flow_count", "10")
        super.fillConfig(config)
    }

    override def beforeTest() {

        vlanBridge = newVlanAwareBridge("vlan-bridge")
        val trunkPort1 = newVlanBridgeTrunkPort(vlanBridge)
        val trunkPort2 = newVlanBridgeTrunkPort(vlanBridge)
        trunk1Id = trunkPort1.getId
        trunk2Id = trunkPort2.getId
        val intVlanPort1 = newInteriorVlanBridgePort(vlanBridge, Some(vlanId1))
        val intVlanPort2 = newInteriorVlanBridgePort(vlanBridge, Some(vlanId2))
        intVlanPort1Id = intVlanPort1.getId
        intVlanPort2Id = intVlanPort2.getId

        buildCommonTopology()

        clusterDataClient().portsLink(intVlanPort1Id, br1IntPort.getId)
        clusterDataClient().portsLink(intVlanPort2Id, br2IntPort.getId)

        initialize()
    }

    /**
     * Tests that the vlan-bridge drops a frame if it brings a vlan-id that
     * is not assigned to one of its logical ports.
     */
    @Test
    override def testFrameFromTrunkWithUnknownVlanId() {
        feedBridgeArpCaches()
        val eth = Packets.udp(trunkMac, vm1_1Mac, trunkIp.toIntIPv4,
            vm1_1Ip.toIntIPv4, 10, 11, "hello".getBytes)
        eth.deserialize(ByteBuffer.wrap(eth.serialize()))
        eth.setVlanID(500)
        injectOnePacket(eth, trunk1Id)
        discardPacketProbe.expectMsgClass(classOf[DiscardPacket])
    }

}
