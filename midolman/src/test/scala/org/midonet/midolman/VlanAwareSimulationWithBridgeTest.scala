package org.midonet.midolman

import java.nio.ByteBuffer

import org.apache.commons.configuration.HierarchicalConfiguration
import org.junit.Test
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import org.midonet.cluster.data.Bridge
import org.midonet.odp.flows.FlowActionOutput
import org.midonet.packets.{Packets, IPv4Addr}

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
        val eth = Packets.arpRequest(trunkMac, trunkIp,
            IPv4Addr.fromString("10.1.1.22"))
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

    /**
     * Basically the same as testFrameExchangeThroughVlanBridge, but will send
     * an additional frame from the trunk to a MAC using the wrong vlan id.
     *
     * So basically, if we have a MAC1 on interior port 1 which has vlan id 1,
     * we'll send a frame to MAC1 using vlan id 2.
     *
     * The expected behaviour is that the frame is dropped.
     *
     * This test doesn't make sense in the VAB test implementation because it
     * does not do mac learning.
     */
    @Test
    def testFrameToWrongPort() {
        feedBridgeArpCaches()
        sendFrame(trunk1Id, List(vm1_1ExtPort.getId), trunkMac,
            vm1_1Mac, trunkIp, vm1_1Ip, vlanId1, vlanOnInject = true)
        sendFrame(trunk1Id, List(vm2_1ExtPort.getId), trunkMac,
            vm2_1Mac, trunkIp, vm2_1Ip, vlanId2, vlanOnInject = true)

        log.debug("The bridge plugged to VM1 has learned trunkMac, active " +
            "trunk port is trunkPort1 ({})", trunk1Id)

        sendFrame(vm1_1ExtPort.getId,
            if (hasMacLearning) List(trunk1Id)
            else List(trunk1Id, trunk2Id),
            vm1_1Mac, trunkMac, vm1_1Ip, trunkIp, vlanId1)
        sendFrame(vm2_1ExtPort.getId,
            if (hasMacLearning) List(trunk1Id)
            else List(trunk1Id, trunk2Id),
            vm2_1Mac, trunkMac, vm2_1Ip, trunkIp, vlanId2)

        log.debug("Sending to VM1's MAC but with the wrong vlan id (2)")
        sendFrame(trunk1Id, List(trunk2Id, vm2_1ExtPort.getId, vm2_2ExtPort.getId), trunkMac, vm1_1Mac,
            trunkIp, vm1_1Ip, vlanId2, vlanOnInject = true)
    }

}
