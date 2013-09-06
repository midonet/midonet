/*
* Copyright 2012 Midokura Europe SARL
*/
package org.midonet.midolman

import guice.CacheModule.{TRACE_INDEX, TRACE_MESSAGES}
import scala.collection.JavaConversions._
import scala.collection.immutable
import scala.collection.mutable.{Map => MMap}
import org.apache.commons.configuration.HierarchicalConfiguration
import org.junit.Test
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import org.midonet.cache.Cache
import org.midonet.midolman.FlowController.{WildcardFlowRemoved, WildcardFlowAdded}
import org.midonet.midolman.PacketWorkflow.PacketIn
import org.midonet.midolman.rules.Condition
import org.midonet.midolman.topology.LocalPortActive
import org.midonet.cluster.data.{Bridge => ClusterBridge}
import org.midonet.cluster.data.ports.MaterializedBridgePort
import org.midonet.cluster.data.zones.GreTunnelZoneHost
import org.midonet.odp.flows.FlowActions
import org.midonet.odp.flows.{FlowActionOutput, FlowActionSetKey, FlowKeyTunnel}
import org.midonet.packets._
import org.midonet.packets.util.PacketBuilder._
import org.midonet.midolman.util.MockCache
import com.google.inject.Key

import java.lang.{Short => JShort}
import org.midonet.midolman.topology.LocalPortActive
import scala.Some
import org.midonet.midolman.FlowController.WildcardFlowAdded
import org.midonet.midolman.PacketWorkflow.PacketIn
import org.midonet.midolman.FlowController.WildcardFlowRemoved


@RunWith(classOf[JUnitRunner])
class BridgeSimulationTestCase extends MidolmanTestCase
        with VirtualConfigurationBuilders {
    private var port1OnHost1: MaterializedBridgePort = null
    private var port2OnHost1: MaterializedBridgePort = null
    private var port3OnHost1: MaterializedBridgePort = null
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

        port1OnHost1 = newExteriorBridgePort(bridge)
        port2OnHost1 = newExteriorBridgePort(bridge)
        port3OnHost1 = newExteriorBridgePort(bridge)
        val portOnHost2 = newExteriorBridgePort(bridge)
        val portOnHost3 = newExteriorBridgePort(bridge)

        materializePort(port1OnHost1, host1, "port1")
        materializePort(portOnHost2, host2, "port2")
        materializePort(portOnHost3, host3, "port3")
        materializePort(port2OnHost1, host1, "port4")
        materializePort(port3OnHost1, host1, "port5")

        List(host1, host2, host3).zip(List(host1Ip, host2Ip, host3Ip)).foreach{
            case (host, ip) =>
                clusterDataClient().tunnelZonesAddMembership(tunnelZone.getId,
                    new GreTunnelZoneHost(host.getId).setIp(ip.toIntIPv4))
        }

        bridgeTunnelTo2 =
            tunnelIsLike(host1Ip.toInt, host2Ip.toInt, bridge.getTunnelKey)
        bridgeTunnelTo3 =
            tunnelIsLike(host1Ip.toInt, host3Ip.toInt, bridge.getTunnelKey)

        clusterDataClient().portSetsAddHost(bridge.getId, host2.getId)
        clusterDataClient().portSetsAddHost(bridge.getId, host3.getId)

        initializeDatapath() should not be (null)

        flowProbe().expectMsgType[DatapathController.DatapathReady].datapath should not be (null)

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

        val addFlowMsg = injectOnePacket(malformed, inputPort, false)
        addFlowMsg.f should not be null
        val flowActs = addFlowMsg.f.getActions
        flowActs should have size(6)

        val (outputs, tunnelKeys) = parseTunnelActions(flowActs)

        outputs should have size(4)
        outputs.contains(FlowActions.output(greTunnelId)) should be (true)
        outputs.contains(FlowActions.output(portId4)) should be (true)
        outputs.contains(FlowActions.output(portId5)) should be (true)

        tunnelKeys should have size(2)
        tunnelKeys.find(bridgeTunnelTo2) should not be None
        tunnelKeys.find(bridgeTunnelTo3) should not be None
    }

    @Test
    def testPacketInBridgeSimulation() {
        val srcMac = MAC.fromString("02:11:22:33:44:10")
        val condition = new Condition
        condition.dlSrc = srcMac
        val conditionList = immutable.Seq[Condition](condition)
        // FIXME(jlm): racy
        deduplicationActor() ! conditionList

        val msgCache = injector.getInstance(Key.get(classOf[Cache],
                                                    classOf[TRACE_MESSAGES]))
                            .asInstanceOf[MockCache]
        val idxCache = injector.getInstance(Key.get(classOf[Cache],
                                                    classOf[TRACE_INDEX]))
                            .asInstanceOf[MockCache]
        idxCache.clear()
        msgCache.clear()
        idxCache.map should have size 0
        msgCache.map should have size 0

        val ethPkt = Packets.udp(
                srcMac,
                MAC.fromString("02:11:22:33:44:11"),
                IPv4Addr.fromString("10.0.1.10"),
                IPv4Addr.fromString("10.0.1.11"),
                10, 11, "My UDP packet".getBytes)
        ethPkt.setVlanIDs(networkVlans)
        val addFlowMsg = injectOnePacket(ethPkt, "port1", false)
        addFlowMsg.f should not be null
        val flowActs = addFlowMsg.f.getActions
        flowActs should have size(6)

        val (outputs, tunnelKeys) = parseTunnelActions(flowActs)

        outputs should have size(4)
        outputs.contains(FlowActions.output(greTunnelId)) should be (true)
        outputs.contains(FlowActions.output(portId4)) should be (true)
        outputs.contains(FlowActions.output(portId5)) should be (true)

        tunnelKeys should have size(2)
        tunnelKeys.find(bridgeTunnelTo2) should not be None
        tunnelKeys.find(bridgeTunnelTo3) should not be None

        idxCache.map should have size 1
        msgCache.map should have size 2

        val cacheMap: MMap[String, MockCache.CacheEntry] = idxCache.map
        val keySet = cacheMap.keySet
        val uuidSet = keySet filter { _.length == 36 }
        uuidSet should have size 1
        val traceID = uuidSet.toArray.apply(0)
        keySet should equal (Set(traceID))
        idxCache.map.get(traceID).value should equal ("2")

        val value1 = msgCache.map.get(traceID + ":1").value
        val value2 = msgCache.map.get(traceID + ":2").value
        value1.substring(23, value1.length) should equal (
            " " + bridge.getId + " Entering device")
        value2.substring(23, value2.length) should equal (
            " " + bridge.getId + " Flooded to port set")

        verifyMacLearned("02:11:22:33:44:10", "port1")
    }

    def testBcastPktBridgeSim() {
        val inputPort = "port1"
        val ethPkt = Packets.udp(
                MAC.fromString("0a:fe:88:70:44:55"),
                MAC.fromString("ff:ff:ff:ff:ff:ff"),
                IPv4Addr.fromString("10.10.10.10"),
                IPv4Addr.fromString("10.11.11.11"),
                10, 12, "Test UDP packet".getBytes)
        ethPkt.setVlanIDs(networkVlans)
        val addFlowMsg = injectOnePacket(ethPkt, inputPort, false)
        val flowActs = addFlowMsg.f.getActions
        flowActs should have size(6)

        val (outputs, tunnelKeys) = parseTunnelActions(flowActs)

        outputs should have size(4)
        outputs.contains(FlowActions.output(greTunnelId)) should be (true)
        outputs.contains(FlowActions.output(portId4)) should be (true)
        outputs.contains(FlowActions.output(portId5)) should be (true)

        tunnelKeys should have size(2)
        tunnelKeys.find(bridgeTunnelTo2) should not be None
        tunnelKeys.find(bridgeTunnelTo3) should not be None
        //Our source MAC should also be learned
        //verifyMacLearned("0a:fe:88:70:44:55", inputPort)
    }

    def testBcastArpBridgeSim() {
        val inputPort = "port1"
        val ethPkt = Packets.arpRequest(
                MAC.fromString("0a:fe:88:90:22:33"),
                IPv4Addr.fromString("10.10.10.11"),
                IPv4Addr.fromString("10.11.11.10"))
        ethPkt.setVlanIDs(networkVlans)
        val addFlowMsg = injectOnePacket(ethPkt, inputPort, false)
        val flowActs = addFlowMsg.f.getActions
        flowActs should have size(6)

        val (outputs, tunnelKeys) = parseTunnelActions(flowActs)

        outputs should have size(4)
        outputs.contains(FlowActions.output(greTunnelId)) should be (true)
        outputs.contains(FlowActions.output(portId4)) should be (true)
        outputs.contains(FlowActions.output(portId5)) should be (true)

        tunnelKeys should have size(2)
        tunnelKeys.find(bridgeTunnelTo2) should not be None
        tunnelKeys.find(bridgeTunnelTo3) should not be None
        //Our source MAC should also be learned
        //verifyMacLearned("0a:fe:88:90:22:33", inputPort)
    }

    def testMcastDstBridgeSim () {
        val inputPort = "port1"
        val ethPkt = Packets.udp(
                MAC.fromString("0a:fe:88:90:22:33"),
                MAC.fromString("01:00:cc:cc:dd:dd"),
                IPv4Addr.fromString("10.10.10.11"),
                IPv4Addr.fromString("10.11.11.10"), 10, 12, "Test UDP Packet".getBytes)
        ethPkt.setVlanIDs(networkVlans)
        val addFlowMsg = injectOnePacket(ethPkt, inputPort, false)
        val flowActs = addFlowMsg.f.getActions
        flowActs should have size(6)

        val (outputs, tunnelKeys) = parseTunnelActions(flowActs)

        outputs should have size(4)
        outputs.contains(FlowActions.output(greTunnelId)) should be (true)
        outputs.contains(FlowActions.output(portId4)) should be (true)
        outputs.contains(FlowActions.output(portId5)) should be (true)

        tunnelKeys should have size(2)
        tunnelKeys.find(bridgeTunnelTo2) should not be None
        tunnelKeys.find(bridgeTunnelTo3) should not be None
    }

    def testMcastSrcBridgeSim () {
        val inputPort = "port1"
        val ethPkt = Packets.udp(
                MAC.fromString("ff:54:ce:50:44:ce"),
                MAC.fromString("0a:de:57:16:a3:06"),
                IPv4Addr.fromString("10.10.10.12"),
                IPv4Addr.fromString("10.11.11.12"),
                10, 12, "Test UDP packet".getBytes)
        ethPkt.setVlanIDs(networkVlans)
        val addFlowMsg = injectOnePacket(ethPkt, inputPort, true)
        val flowActs = addFlowMsg.f.getActions
        flowActs should have size(0)
    }

    def testMacMigrationBridgeSim () {
        var inputPort = "port1"
        var ethPkt = Packets.udp(
                MAC.fromString("02:13:66:77:88:99"),
                MAC.fromString("02:11:22:33:44:55"),
                IPv4Addr.fromString("10.0.1.10"),
                IPv4Addr.fromString("10.0.1.11"),
                10, 11, "My UDP packet".getBytes)
        ethPkt.setVlanIDs(networkVlans)
        var addFlowMsg = injectOnePacket(ethPkt, inputPort, true)
        addFlowMsg.f should not be null
        var flowActs = addFlowMsg.f.getActions
        flowActs should have size(6)

        val (outputs, tunnelKeys) = parseTunnelActions(flowActs)

        outputs should have size(4)
        outputs.contains(FlowActions.output(greTunnelId)) should be (true)
        outputs.contains(FlowActions.output(portId4)) should be (true)
        outputs.contains(FlowActions.output(portId5)) should be (true)

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
        addFlowMsg = injectOnePacket(ethPkt, inputPort, true)
        addFlowMsg.f should not be null
        flowActs = addFlowMsg.f.getActions
        flowActs should have size(1)

        val (outputs2, tunnelKeys2) = parseTunnelActions(flowActs)
        outputs2 should have size 1
        outputs2.contains(FlowActions.output(portId4)) should be (true)
        tunnelKeys2 should have size 0

        requestOfType[WildcardFlowRemoved](wflowRemovedProbe)

        verifyMacLearned("02:13:66:77:88:99", "port5")
    }

    private def injectOnePacket (ethPkt : Ethernet, ingressPortName : String,
                                 isDropExpected: Boolean) : WildcardFlowAdded = {

        triggerPacketIn(ingressPortName, ethPkt)

        val pktInMsg = packetInProbe.expectMsgClass(classOf[PacketIn])
        val ingressPort = getMaterializedPort(ingressPortName)

        pktInMsg should not be null
        pktInMsg.eth should not be null
        pktInMsg.wMatch should not be null
        // We're racing with DatapathController here. DC's job is to remove
        // the inputPortUUID field and set the corresponding inputPort (short).
        if (pktInMsg.wMatch.getInputPortUUID != null)
            pktInMsg.wMatch.getInputPortUUID should be(ingressPort.getId)
        else
            pktInMsg.wMatch.getInputPort should
                be(getPortNumber(ingressPortName))

        wflowAddedProbe.expectMsgClass(classOf[WildcardFlowAdded])
    }

    private def getMaterializedPort (portName : String) : MaterializedBridgePort = {
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
        val addFlowMsg = injectOnePacket(ethPkt, "port4", isDropExpected = false)
        val expectedPort = getMaterializedPort(expectedPortName)
        val flowActs = addFlowMsg.f.getActions
        flowActs should have size(1)
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
