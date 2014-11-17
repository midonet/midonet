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

import java.lang.{Short => JShort}
import scala.collection.immutable
import scala.collection.JavaConversions._

import com.google.inject.Key
import org.apache.commons.configuration.HierarchicalConfiguration
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.slf4j.LoggerFactory

import org.midonet.cluster.data.ports.BridgePort
import org.midonet.cluster.data.{Bridge => ClusterBridge, TunnelZone}
import org.midonet.midolman.FlowController._
import org.midonet.midolman.PacketWorkflow.PacketIn
import org.midonet.midolman.rules.{RuleResult, Condition}
import org.midonet.midolman.topology.LocalPortActive
import org.midonet.midolman.util.MidolmanTestCase
import org.midonet.midolman.util.SimulationHelper
import org.midonet.odp.flows.{FlowActionOutput, FlowKeyTunnel}
import org.midonet.odp.flows.FlowActions.output
import org.midonet.packets._
import org.midonet.packets.util.PacketBuilder._

@Category(Array(classOf[SimulationTests]))
@RunWith(classOf[JUnitRunner])
class BridgeSimulationTestCase extends MidolmanTestCase
        with SimulationHelper {

    val log = LoggerFactory.getLogger(classOf[BridgeSimulationTestCase])
    private var port1OnHost1: BridgePort = null
    private var port2OnHost1: BridgePort = null
    private var port3OnHost1: BridgePort = null

    private var bridge: ClusterBridge = null
    private var portId4 : Short = 0
    private var portId5 : Short = 0
    private var tunnelId1: Short = 0
    private var tunnelId2: Short = 0

    val host1Ip = IPv4Addr("192.168.100.1")
    val host2Ip = IPv4Addr("192.168.125.1")
    val host3Ip = IPv4Addr("192.168.150.1")

    var bridgeTunnelTo2: FlowKeyTunnel => Boolean = null
    var bridgeTunnelTo3: FlowKeyTunnel => Boolean = null

    override protected def fillConfig(config: HierarchicalConfiguration) = {
        config.setProperty("datapath.max_flow_count", "10")
        super.fillConfig(config)
    }

    override def beforeTest() {
        val tunnelZone = greTunnelZone("default")

        val host1 = newHost("host1", hostId())
        val host2 = newHost("host2")
        val host3 = newHost("host3")

        bridge = newBridge("bridge")

        port1OnHost1 = newBridgePort(bridge)
        port2OnHost1 = newBridgePort(bridge)
        port3OnHost1 = newBridgePort(bridge)
        val portOnHost2 = newBridgePort(bridge)
        val portOnHost3 = newBridgePort(bridge)

        materializePort(port1OnHost1, host1, "port1")
        materializePort(portOnHost2, host2, "port2")
        materializePort(portOnHost3, host3, "port3")
        materializePort(port2OnHost1, host1, "port4")
        materializePort(port3OnHost1, host1, "port5")

        List(host1, host2, host3).zip(List(host1Ip, host2Ip, host3Ip)).foreach{
            case (host, ip) =>
                clusterDataClient().tunnelZonesAddMembership(tunnelZone.getId,
                    new TunnelZone.HostConfig(host.getId).setIp(ip))
        }

        bridgeTunnelTo2 =
            tunnelIsLike(host1Ip.toInt, host2Ip.toInt, portOnHost2.getTunnelKey)
        bridgeTunnelTo3 =
            tunnelIsLike(host1Ip.toInt, host3Ip.toInt, portOnHost3.getTunnelKey)

        initializeDatapath() should not be (null)

        datapathEventsProbe.expectMsgType[DatapathController.DatapathReady]
            .datapath should not be (null)

        // assert first that vports are up with their receiving flows installed
        wflowAddedProbe.expectMsgClass(classOf[WildcardFlowAdded])
        wflowAddedProbe.expectMsgClass(classOf[WildcardFlowAdded])
        wflowAddedProbe.expectMsgClass(classOf[WildcardFlowAdded])
        portsProbe.expectMsgClass(classOf[LocalPortActive])
        portsProbe.expectMsgClass(classOf[LocalPortActive])
        portsProbe.expectMsgClass(classOf[LocalPortActive])

        drainProbes()

        vifToLocalPortNumber(port2OnHost1.getId) match {
            case Some(portNo : Short) => portId4 = portNo
            case None => fail("Not able to find data port number for materialize Port 4")
        }
        vifToLocalPortNumber(port3OnHost1.getId) match {
            case Some(portNo : Short) => portId5 = portNo
            case None => fail("Not able to find data port number for materialize Port 5")
        }
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

    @Test
    def testMalformedL3() {
        val inputPort = "port1"
        val malformed = eth mac "02:11:22:33:44:10" -> "02:11:22:33:44:20"
        malformed << payload("00:00")
        malformed ether_type IPv4.ETHERTYPE vlans networkVlans

        val flowActs = injectOnePacket(malformed, inputPort).getActions
        flowActs should have size(6)

        val (outputs, tunnelKeys) = parseTunnelActions(flowActs)

        outputs should have size(4)
        outputs.contains(output(greTunnelId)) should be (true)
        outputs.contains(output(portId4)) should be (true)
        outputs.contains(output(portId5)) should be (true)

        tunnelKeys should have size(2)
        tunnelKeys.find(bridgeTunnelTo2) should not be None
        tunnelKeys.find(bridgeTunnelTo3) should not be None
    }

    @Test
    def testPacketInBridgeSimulation() {
        val srcMac = MAC.fromString("02:11:22:33:44:10")
        val ethPkt = Packets.udp(
                srcMac,
                MAC.fromString("02:11:22:33:44:11"),
                IPv4Addr.fromString("10.0.1.10"),
                IPv4Addr.fromString("10.0.1.11"),
                10, 11, "My UDP packet".getBytes)
        ethPkt.setVlanIDs(networkVlans)
        val flowActs = injectOnePacket(ethPkt, "port1").getActions
        flowActs should have size(6)

        val (outputs, tunnelKeys) = parseTunnelActions(flowActs)

        outputs should have size(4)
        outputs.contains(output(greTunnelId)) should be (true)
        outputs.contains(output(portId4)) should be (true)
        outputs.contains(output(portId5)) should be (true)

        tunnelKeys should have size(2)
        tunnelKeys.find(bridgeTunnelTo2) should not be None
        tunnelKeys.find(bridgeTunnelTo3) should not be None

        verifyMacLearned("02:11:22:33:44:10", "port1")
    }

    @Test
    def testInboundChainsNotAppliedToVlanTraffic() {
        // setup chains that drop all UDP traffic both at pre and post
        val udpCond = new Condition()
        udpCond.nwProto = Byte.box(UDP.PROTOCOL_NUMBER)

        val preChain = newInboundChainOnBridge("brFilter-in", bridge)
        newLiteralRuleOnChain(preChain, 1,udpCond, RuleResult.Action.DROP)

        checkTrafficWithDropChains()
    }

    def testOutboundChainsNotAppliedToVlanTraffic() {

        // setup chains that drop all UDP traffic both at pre and post
        val udpCond = new Condition()
        udpCond.nwProto = Byte.box(UDP.PROTOCOL_NUMBER)

        val postChain = newOutboundChainOnBridge("brFilter-out", bridge)
        newLiteralRuleOnChain(postChain, 1, udpCond, RuleResult.Action.DROP)

        checkTrafficWithDropChains()
    }

    /**
     * Use after setting chains that drop UDP traffic on a bridge. The method
     * will send traffic through the bridge and expect it dropped (by matching
     * on the chains) if it is vlan-tagged, but not dropped otherwise.
     */
    private def checkTrafficWithDropChains() {

        val inputPort = "port1"
        val ethPkt = Packets.udp(
            MAC.fromString("0a:fe:88:70:44:55"),
            MAC.fromString("ff:ff:ff:ff:ff:ff"),
            IPv4Addr.fromString("10.10.10.10"),
            IPv4Addr.fromString("10.11.11.11"),
            10, 12, "Test UDP packet".getBytes)
        ethPkt.setVlanIDs(networkVlans)

        log.info("Testing traffic with vlans: {}", networkVlans)

        val flowActs = injectOnePacket(ethPkt, inputPort).getActions
        if (networkVlans.isEmpty) {
            // non-vlan traffic should apply the rules, match, be dropped
            flowActs should have size(0)
        } else {
            // vlan traffic should NOT match on the rules, so it'll pass
            flowActs should have size(6)

            val (outputs, tunnelKeys) = parseTunnelActions(flowActs)

            outputs should have size(4)
            outputs.contains(output(greTunnelId)) should be (true)
            outputs.contains(output(portId4)) should be (true)
            outputs.contains(output(portId5)) should be (true)

            tunnelKeys should have size(2)
            tunnelKeys.find(bridgeTunnelTo2) should not be None
            tunnelKeys.find(bridgeTunnelTo3) should not be None
        }
    }

    @Test
    def testBcastPktBridgeSim() {
        val inputPort = "port1"
        val ethPkt = Packets.udp(
                MAC.fromString("0a:fe:88:70:44:55"),
                MAC.fromString("ff:ff:ff:ff:ff:ff"),
                IPv4Addr.fromString("10.10.10.10"),
                IPv4Addr.fromString("10.11.11.11"),
                10, 12, "Test UDP packet".getBytes)
        ethPkt.setVlanIDs(networkVlans)
        val flowActs = injectOnePacket(ethPkt, inputPort).getActions
        flowActs should have size(6)

        val (outputs, tunnelKeys) = parseTunnelActions(flowActs)

        outputs should have size(4)
        outputs.contains(output(greTunnelId)) should be (true)
        outputs.contains(output(portId4)) should be (true)
        outputs.contains(output(portId5)) should be (true)

        tunnelKeys should have size(2)
        tunnelKeys.find(bridgeTunnelTo2) should not be None
        tunnelKeys.find(bridgeTunnelTo3) should not be None
        //Our source MAC should also be learned
        //verifyMacLearned("0a:fe:88:70:44:55", inputPort)
    }

    @Test
    def testBcastArpBridgeSim() {
        val inputPort = "port1"
        val ethPkt = Packets.arpRequest(
                MAC.fromString("0a:fe:88:90:22:33"),
                IPv4Addr.fromString("10.10.10.11"),
                IPv4Addr.fromString("10.11.11.10"))
        ethPkt.setVlanIDs(networkVlans)
        val flowActs = injectOnePacket(ethPkt, inputPort).getActions
        flowActs should have size(6)

        val (outputs, tunnelKeys) = parseTunnelActions(flowActs)

        outputs should have size(4)
        outputs.contains(output(greTunnelId)) should be (true)
        outputs.contains(output(portId4)) should be (true)
        outputs.contains(output(portId5)) should be (true)

        tunnelKeys should have size(2)
        tunnelKeys.find(bridgeTunnelTo2) should not be None
        tunnelKeys.find(bridgeTunnelTo3) should not be None
        //Our source MAC should also be learned
        //verifyMacLearned("0a:fe:88:90:22:33", inputPort)
    }

    @Test
    def testMcastDstBridgeSim () {
        val inputPort = "port1"
        val ethPkt = Packets.udp(
                MAC.fromString("0a:fe:88:90:22:33"),
                MAC.fromString("01:00:cc:cc:dd:dd"),
                IPv4Addr.fromString("10.10.10.11"),
                IPv4Addr.fromString("10.11.11.10"), 10, 12, "Test UDP Packet".getBytes)
        ethPkt.setVlanIDs(networkVlans)
        val flowActs = injectOnePacket(ethPkt, inputPort).getActions
        flowActs should have size(6)

        val (outputs, tunnelKeys) = parseTunnelActions(flowActs)

        outputs should have size(4)
        outputs.contains(output(greTunnelId)) should be (true)
        outputs.contains(output(portId4)) should be (true)
        outputs.contains(output(portId5)) should be (true)

        tunnelKeys should have size(2)
        tunnelKeys.find(bridgeTunnelTo2) should not be None
        tunnelKeys.find(bridgeTunnelTo3) should not be None
    }

    @Test
    def testMcastSrcBridgeSim () {
        val inputPort = "port1"
        val ethPkt = Packets.udp(
                MAC.fromString("ff:54:ce:50:44:ce"),
                MAC.fromString("0a:de:57:16:a3:06"),
                IPv4Addr.fromString("10.10.10.12"),
                IPv4Addr.fromString("10.11.11.12"),
                10, 12, "Test UDP packet".getBytes)
        ethPkt.setVlanIDs(networkVlans)
        injectOnePacket(ethPkt, inputPort).getActions should have size(0)
    }

    @Test
    def testMacMigrationBridgeSim () {
        var inputPort = "port1"
        var ethPkt = Packets.udp(
                MAC.fromString("02:13:66:77:88:99"),
                MAC.fromString("02:11:22:33:44:55"),
                IPv4Addr.fromString("10.0.1.10"),
                IPv4Addr.fromString("10.0.1.11"),
                10, 11, "My UDP packet".getBytes)
        ethPkt.setVlanIDs(networkVlans)
        var flowActs = injectOnePacket(ethPkt, inputPort).getActions
        flowActs should have size(6)

        val (outputs, tunnelKeys) = parseTunnelActions(flowActs)

        outputs should have size(4)
        outputs.contains(output(greTunnelId)) should be (true)
        outputs.contains(output(portId4)) should be (true)
        outputs.contains(output(portId5)) should be (true)

        tunnelKeys should have size(2)
        tunnelKeys.find(bridgeTunnelTo2) should not be None
        tunnelKeys.find(bridgeTunnelTo3) should not be None

        verifyMacLearned("02:13:66:77:88:99", "port1")
        /*
         * MAC moved from port1 to port5
         * frame is going toward port4 (the learned MAC from verifyMacLearned)
         */
        inputPort = "port5"
        ethPkt = Packets.udp(
                     MAC.fromString("02:13:66:77:88:99"),
                     MAC.fromString("0a:fe:88:70:33:ab"),
                     IPv4Addr.fromString("10.0.1.10"),
                     IPv4Addr.fromString("10.0.1.11"),
                     10, 11, "My UDP packet".getBytes)
        ethPkt.setVlanIDs(networkVlans)
        flowActs = injectOnePacket(ethPkt, inputPort).getActions
        flowActs should have size(1)

        val (outputs2, tunnelKeys2) = parseTunnelActions(flowActs)
        outputs2 should have size 1
        outputs2.contains(output(portId4)) should be (true)
        tunnelKeys2 should have size 0

        requestOfType[WildcardFlowRemoved](wflowRemovedProbe)

        verifyMacLearned("02:13:66:77:88:99", "port5")

    }

    private def injectOnePacket(ethPkt: Ethernet, port: String) = {
        drainProbes()
        triggerPacketIn(port, ethPkt)
        verifyPacketIn(packetInProbe.expectMsgClass(classOf[PacketIn]), port)
        //wflowAddedProbe.expectMsgClass(classOf[WildcardFlowAdded])
        ackWCAdded()
    }

    private def verifyPacketIn(pktInMsg: PacketIn, port: String) {
        pktInMsg should not be null
        pktInMsg.eth should not be null
        pktInMsg.wMatch should not be null

        // We're racing with DatapathController here. DC's job is to remove
        // the inputPortUUID field and set the corresponding inputPort (short).
        val portUUID = pktInMsg.inputPort
        if (portUUID != null)
            portUUID should be(getBridgePort(port).getId)
        else
            pktInMsg.wMatch.getInputPortNumber should be(getPortNumber(port))
    }

    private def getBridgePort (portName : String) : BridgePort = {
        portName match {
            case "port1" => port1OnHost1
            case "port4" => port2OnHost1
            case "port5" => port3OnHost1
        }
    }

    /*
     * In this test, always assume input port is port4 (keep src mac
     * consistent), and dst mac should always go out toward port1
     */
    private def verifyMacLearned(learnedMac : String, expectedPortName : String) {
        val ethPkt = Packets.udp(
                MAC.fromString("0a:fe:88:70:33:ab"),
                MAC.fromString(learnedMac),
                IPv4Addr.fromString("10.10.10.10"),
                IPv4Addr.fromString("10.11.11.11"),
                10, 12, "Test UDP packet".getBytes)
        ethPkt.setVlanIDs(networkVlans)
        val flowActs = injectOnePacket(ethPkt, "port4").getActions
        flowActs should have size(1)
        val expectedPort = getBridgePort(expectedPortName)
        vifToLocalPortNumber(expectedPort.getId) match {
            case Some(portNo : Short) =>
                as[FlowActionOutput](flowActs(0)).getPortNumber should equal (portNo)
            case None => fail("Not able to find data port number for" +
                              "materialized Port " + expectedPort.getId)
        }
    }
}

/**
  * The same tests as the parent
  * [[org.midonet.midolman.BridgeSimulationTestCase]], but transmitting frames
  * that have one vlan id.
  *
  * The tests are expected to work in exactly the same way, since here we're
  * adding a Vlan Unaware Bridge (all its interior ports are not vlan-tagged)
  * and therefore it should behave with vlan traffic in the same way as if it
  * was not tagged.
  */
class BridgeSimulationTestCaseWithOneVlan extends BridgeSimulationTestCase {
    override def networkVlans: List[JShort] = List(2.toShort)
}

/**
  * The same tests [[org.midonet.midolman.BridgeSimulationTestCase]], but
  * transmitting frames that have one vlan id.
  */
class BridgeSimulationTestCaseWithManyVlans extends BridgeSimulationTestCase {
    override def networkVlans: List[JShort] = List(3,4,5,6) map {
        x => short2Short(x.toShort)
    }
}
