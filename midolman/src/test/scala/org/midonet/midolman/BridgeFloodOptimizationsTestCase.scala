/*
* Copyright 2012 Midokura Europe SARL
*/
package org.midonet.midolman

import scala.collection.JavaConversions._

import akka.testkit.TestProbe
import org.apache.commons.configuration.HierarchicalConfiguration
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import org.midonet.cluster.data.Bridge
import org.midonet.cluster.data.ports.BridgePort
import org.midonet.cluster.data.{Bridge => ClusterBridge}
import org.midonet.midolman.FlowController.WildcardFlowAdded
import org.midonet.midolman.topology.LocalPortActive
import org.midonet.midolman.util.MidolmanTestCase
import org.midonet.midolman.util.SimulationHelper
import org.midonet.packets._

@Category(Array(classOf[SimulationTests]))
@RunWith(classOf[JUnitRunner])
class BridgeFloodOptimizationsTestCase extends MidolmanTestCase
        with SimulationHelper {
    private var flowEventsProbe: TestProbe = null
    private var packetEventsProbe: TestProbe = null
    private var port1: BridgePort = null
    private var port2: BridgePort = null
    private var port3: BridgePort = null
    private var bridge: ClusterBridge = null
    private var portId1 : Short = 0
    private var portId2 : Short = 0
    private var portId3 : Short = 0
    val mac1 = MAC.fromString("02:11:11:11:11:09")
    val ip1 = IPv4Addr.fromString("10.0.1.1")

    override protected def fillConfig(config: HierarchicalConfiguration) = {
        config.setProperty("datapath.max_flow_count", "10")
        config.setProperty("midolman.enable_bridge_arp", true)
        super.fillConfig(config)
    }

    override def beforeTest() {
        val host1 = newHost("host1", hostId())

        bridge = newBridge("bridge")
        port1 = newBridgePort(bridge)
        port2 = newBridgePort(bridge)
        port3 = newBridgePort(bridge)

        materializePort(port1, host1, "port1")
        materializePort(port2, host1, "port2")
        materializePort(port3, host1, "port3")
        // Seed the bridge with mac, ip, vport for port1.
        clusterDataClient().bridgeAddIp4Mac(bridge.getId, ip1, mac1)
        clusterDataClient().bridgeAddMacPort(
            bridge.getId, Bridge.UNTAGGED_VLAN_ID, mac1, port1.getId)

        flowEventsProbe = newProbe()
        packetEventsProbe = newProbe()
        val portEventsProbe = newProbe()
        actors().eventStream.subscribe(flowEventsProbe.ref, classOf[WildcardFlowAdded])
        actors().eventStream.subscribe(portEventsProbe.ref, classOf[LocalPortActive])
        actors().eventStream.subscribe(packetEventsProbe.ref, classOf[PacketsExecute])

        initializeDatapath() should not be (null)
        flowProbe().expectMsgType[DatapathController.DatapathReady].datapath should not be (null)

        flowEventsProbe.expectMsgClass(classOf[WildcardFlowAdded])
        flowEventsProbe.expectMsgClass(classOf[WildcardFlowAdded])
        flowEventsProbe.expectMsgClass(classOf[WildcardFlowAdded])
        portEventsProbe.expectMsgClass(classOf[LocalPortActive])
        portEventsProbe.expectMsgClass(classOf[LocalPortActive])
        portEventsProbe.expectMsgClass(classOf[LocalPortActive])
        drainProbes()

        vifToLocalPortNumber(port1.getId) match {
            case Some(portNo : Short) => portId1 = portNo
            case None =>
                fail("Data port number for materialize Port 1 not found")
        }
        vifToLocalPortNumber(port2.getId) match {
            case Some(portNo : Short) => portId2 = portNo
            case None =>
                fail("Data port number for materialize Port 2 not found")
        }
        vifToLocalPortNumber(port3.getId) match {
            case Some(portNo : Short) => portId3 = portNo
            case None =>
                fail("Data port number for materialize Port 3 not found")
        }
    }

    def testNoFlood() {
        val dpFlowProbe = newProbe()
        actors().eventStream.subscribe(dpFlowProbe.ref,
            classOf[FlowAdded])
        actors().eventStream.subscribe(dpFlowProbe.ref,
            classOf[FlowRemoved])

        // Make an ARP request
        val ingressPortName = "port2"
        val mac2 = MAC.fromString("0a:fe:88:90:22:33")
        val ip2 = IPv4Addr.fromString("10.10.10.11")
        var ethPkt = Packets.arpRequest(mac2, ip2, ip1)
        triggerPacketIn(ingressPortName, ethPkt)
        // The bridge should generate the ARP reply and emit to port2.
        var pktOut = packetEventsProbe.expectMsgClass(classOf[PacketsExecute])
        pktOut.packet.getData should be (ARP.makeArpReply(
            mac1, mac2, IPv4Addr.intToBytes(ip1.addr),
            IPv4Addr.intToBytes(ip2.addr)).serialize())
        var outports = actionsToOutputPorts(pktOut.actions)
        outports should have size (1)
        outports should (contain (portId2))
        // one packet should have been forwarded; no flows installed
        mockDpConn().flowsTable.size() should be(0)
        mockDpConn().packetsSent.size() should be (1)
        mockDpConn().packetsSent.get(0) should be (pktOut.packet)

        // If a packet is sent to mac1 it's forwarded to port1, not flooded.
        ethPkt = Packets.udp(mac2, mac1, ip2, ip1, 10, 12, "Test".getBytes)
        triggerPacketIn(ingressPortName, ethPkt)
        // expect one wild flow to be added
        var wflow = flowEventsProbe.expectMsgClass(classOf[WildcardFlowAdded]).f
        outports = actionsToOutputPorts(wflow.actions)
        outports should have size (1)
        outports should (contain (portId1))
        // one dp flow should also have been added and one packet forwarded
        dpFlowProbe.expectMsgClass(classOf[FlowAdded])
        mockDpConn().flowsTable.size() should be(1)

        pktOut = packetEventsProbe.expectMsgClass(classOf[PacketsExecute])
        (pktOut.actions.toArray) should be (wflow.getActions.toArray)
        pktOut.packet.getData should be (ethPkt.serialize())
        mockDpConn().packetsSent.size() should be (2)
        mockDpConn().packetsSent.get(1) should be (pktOut.packet)

        // If a packet is sent to mac3 it's flooded (mac3 hasn't been learned).
        val mac3 = MAC.fromString("0a:fe:88:90:ee:ee")
        val ip3 = IPv4Addr.fromString("10.10.10.13")
        ethPkt = Packets.udp(mac2, mac3, ip2, ip3, 10, 12, "Test".getBytes)
        triggerPacketIn(ingressPortName, ethPkt)
        // expect one wild flow to be added
        wflow = flowEventsProbe.expectMsgClass(classOf[WildcardFlowAdded]).f
        outports = actionsToOutputPorts(wflow.getActions)
        outports should have size (2)
        outports should (contain (portId1) and contain (portId3))

        // one dp flow should also have been added and one packet forwarded
        dpFlowProbe.expectMsgClass(classOf[FlowAdded])
        mockDpConn().flowsTable.size() should be(2)

        pktOut = packetEventsProbe.expectMsgClass(classOf[PacketsExecute])
        pktOut.actions.toArray should be (wflow.getActions.toArray)
        pktOut.packet.getData should be (ethPkt.serialize())
        mockDpConn().packetsSent.size() should be (3)
        mockDpConn().packetsSent.get(2) should be (pktOut.packet)
    }
}
