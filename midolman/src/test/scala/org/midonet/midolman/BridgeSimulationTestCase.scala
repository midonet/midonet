/*
* Copyright 2012 Midokura Europe SARL
*/
package org.midonet.midolman

import scala.collection.JavaConversions._
import scala.collection.mutable.{Map => MMap}
import akka.testkit.TestProbe
import org.apache.commons.configuration.HierarchicalConfiguration
import org.junit.Test
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import org.midonet.cache.Cache
import org.midonet.midolman.DatapathController.TunnelChangeEvent
import org.midonet.midolman.FlowController.{WildcardFlowRemoved, WildcardFlowAdded}
import org.midonet.midolman.PacketWorkflow.PacketIn
import org.midonet.midolman.guice.DummyConditionSetModule
import org.midonet.midolman.topology.LocalPortActive
import org.midonet.cluster.data.{Bridge => ClusterBridge}
import org.midonet.cluster.data.ports.MaterializedBridgePort
import org.midonet.cluster.data.zones.GreTunnelZoneHost
import org.midonet.odp.flows.{FlowActionOutput, FlowActions, FlowActionSetKey,
    FlowKeyTunnelID}
import org.midonet.packets.{Ethernet, IntIPv4, MAC, Packets}
import org.midonet.midolman.util.MockCache


@RunWith(classOf[JUnitRunner])
class BridgeSimulationTestCase extends MidolmanTestCase
        with VirtualConfigurationBuilders {
    private var tunnelEventsProbe: TestProbe = null
    private var port1OnHost1: MaterializedBridgePort = null
    private var port2OnHost1: MaterializedBridgePort = null
    private var port3OnHost1: MaterializedBridgePort = null
    private var bridge: ClusterBridge = null
    private var portId4 : Short = 0
    private var portId5 : Short = 0
    private var tunnelId1: Short = 0
    private var tunnelId2: Short = 0

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

        clusterDataClient().tunnelZonesAddMembership(
            tunnelZone.getId,
            new GreTunnelZoneHost(host1.getId)
                .setIp(IntIPv4.fromString("192.168.100.1")))
        clusterDataClient().tunnelZonesAddMembership(
            tunnelZone.getId,
            new GreTunnelZoneHost(host2.getId)
                .setIp(IntIPv4.fromString("192.168.125.1")))
        clusterDataClient().tunnelZonesAddMembership(
            tunnelZone.getId,
            new GreTunnelZoneHost(host3.getId)
                .setIp(IntIPv4.fromString("192.168.150.1")))

        clusterDataClient().portSetsAddHost(bridge.getId, host2.getId)
        clusterDataClient().portSetsAddHost(bridge.getId, host3.getId)

        tunnelEventsProbe = newProbe()
        actors().eventStream.subscribe(tunnelEventsProbe.ref,
                                       classOf[TunnelChangeEvent])

        initializeDatapath() should not be (null)

        flowProbe().expectMsgType[DatapathController.DatapathReady].datapath should not be (null)

        tunnelId1 = tunnelEventsProbe.expectMsgClass(classOf[TunnelChangeEvent]).portOption.get
        tunnelId2 = tunnelEventsProbe.expectMsgClass(classOf[TunnelChangeEvent]).portOption.get
        wflowAddedProbe.expectMsgClass(classOf[WildcardFlowAdded])
        wflowAddedProbe.expectMsgClass(classOf[WildcardFlowAdded])
        wflowAddedProbe.expectMsgClass(classOf[WildcardFlowAdded])
        portsProbe.expectMsgClass(classOf[LocalPortActive])
        portsProbe.expectMsgClass(classOf[LocalPortActive])
        portsProbe.expectMsgClass(classOf[LocalPortActive])
        drainProbes()

        dpController().underlyingActor.vifToLocalPortNumber(port2OnHost1.getId) match {
            case Some(portNo : Short) => portId4 = portNo
            case None => fail("Not able to find data port number for materialize Port 4")
        }
        dpController().underlyingActor.vifToLocalPortNumber(port3OnHost1.getId) match {
            case Some(portNo : Short) => portId5 = portNo
            case None => fail("Not able to find data port number for materialize Port 5")
        }
    }

    override def getConditionSetModule = new DummyConditionSetModule(true)

    @Test
    def testPacketInBridgeSimulation() {
        val cache = injector.getInstance(classOf[Cache]).asInstanceOf[MockCache]
        cache.clear()
        cache.map.size should equal (0)

        val ethPkt = Packets.udp(
                MAC.fromString("02:11:22:33:44:10"),
                MAC.fromString("02:11:22:33:44:11"),
                IntIPv4.fromString("10.0.1.10"),
                IntIPv4.fromString("10.0.1.11"),
                10, 11, "My UDP packet".getBytes)
        val addFlowMsg = injectOnePacket(ethPkt, "port1", false)
        addFlowMsg.f should not be null
        val flowActs = addFlowMsg.f.getActions
        flowActs should have size(5)
        as[FlowActionSetKey](flowActs(2)).getFlowKey should equal (
            new FlowKeyTunnelID().setTunnelID(bridge.getTunnelKey))
        flowActs.contains(FlowActions.output(portId4)) should be (true)
        flowActs.contains(FlowActions.output(portId5)) should be (true)
        flowActs.contains(FlowActions.output(tunnelId1)) should be (true)
        flowActs.contains(FlowActions.output(tunnelId2)) should be (true)

        cache.map.size should equal (3)
        val cacheMap: MMap[String, MockCache.CacheEntry] = cache.map
        val keySet = cacheMap.keySet
        val uuidSet = keySet filter { _.length == 36 }
        uuidSet.size should equal (1)
        val traceID = uuidSet.toArray.apply(0)
        keySet should equal (Set(traceID, traceID + ":1", traceID + ":2"))
        cacheMap.get(traceID).get.value should equal ("2")
        val value1 = cacheMap.get(traceID + ":1").get.value
        val value2 = cacheMap.get(traceID + ":2").get.value
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
                IntIPv4.fromString("10.10.10.10"),
                IntIPv4.fromString("10.11.11.11"),
                10, 12, "Test UDP packet".getBytes)
        val addFlowMsg = injectOnePacket(ethPkt, inputPort, false)
        val flowActs = addFlowMsg.f.getActions
        flowActs should have size(5)
        as[FlowActionSetKey](flowActs(2)).getFlowKey should equal (
            new FlowKeyTunnelID().setTunnelID(bridge.getTunnelKey))
        flowActs.contains(FlowActions.output(portId4)) should be (true)
        flowActs.contains(FlowActions.output(portId5)) should be (true)
        flowActs.contains(FlowActions.output(tunnelId1)) should be (true)
        flowActs.contains(FlowActions.output(tunnelId2)) should be (true)
        //Our source MAC should also be learned
        //verifyMacLearned("0a:fe:88:70:44:55", inputPort)
    }

    def testBcastArpBridgeSim() {
        val inputPort = "port1"
        val ethPkt = Packets.arpRequest(
                MAC.fromString("0a:fe:88:90:22:33"),
                IntIPv4.fromString("10.10.10.11"),
                IntIPv4.fromString("10.11.11.10"))
        val addFlowMsg = injectOnePacket(ethPkt, inputPort, false)
        val flowActs = addFlowMsg.f.getActions
        flowActs should have size(5)
        as[FlowActionSetKey](flowActs(2)).getFlowKey should equal (
            new FlowKeyTunnelID().setTunnelID(bridge.getTunnelKey))
        flowActs.contains(FlowActions.output(portId4)) should be (true)
        flowActs.contains(FlowActions.output(portId5)) should be (true)
        flowActs.contains(FlowActions.output(tunnelId2)) should be (true)
        flowActs.contains(FlowActions.output(tunnelId2)) should be (true)
        //Our source MAC should also be learned
        //verifyMacLearned("0a:fe:88:90:22:33", inputPort)
    }

    def testMcastDstBridgeSim () {
        val inputPort = "port1"
        val ethPkt = Packets.udp(
                MAC.fromString("0a:fe:88:90:22:33"),
                MAC.fromString("01:00:cc:cc:dd:dd"),
                IntIPv4.fromString("10.10.10.11"),
                IntIPv4.fromString("10.11.11.10"), 10, 12, "Test UDP Packet".getBytes)
        val addFlowMsg = injectOnePacket(ethPkt, inputPort, false)
        val flowActs = addFlowMsg.f.getActions
        flowActs should have size(5)
        as[FlowActionSetKey](flowActs(2)).getFlowKey should equal (
            new FlowKeyTunnelID().setTunnelID(bridge.getTunnelKey))
        flowActs.contains(FlowActions.output(portId4)) should be (true)
        flowActs.contains(FlowActions.output(portId5)) should be (true)
        flowActs.contains(FlowActions.output(tunnelId1)) should be (true)
        flowActs.contains(FlowActions.output(tunnelId2)) should be (true)
    }

    def testMcastSrcBridgeSim () {
        val inputPort = "port1"
        val ethPkt = Packets.udp(
                MAC.fromString("ff:54:ce:50:44:ce"),
                MAC.fromString("0a:de:57:16:a3:06"),
                IntIPv4.fromString("10.10.10.12"),
                IntIPv4.fromString("10.11.11.12"),
                10, 12, "Test UDP packet".getBytes)
        val addFlowMsg = injectOnePacket(ethPkt, inputPort, true)
        val flowActs = addFlowMsg.f.getActions
        flowActs should have size(0)
    }

    def testMacMigrationBridgeSim () {
        var inputPort = "port1"
        var ethPkt = Packets.udp(
                MAC.fromString("02:13:66:77:88:99"),
                MAC.fromString("02:11:22:33:44:55"),
                IntIPv4.fromString("10.0.1.10"),
                IntIPv4.fromString("10.0.1.11"),
                10, 11, "My UDP packet".getBytes)
        var addFlowMsg = injectOnePacket(ethPkt, inputPort, true)
        addFlowMsg.f should not be null
        var flowActs = addFlowMsg.f.getActions
        flowActs should have size(5)
        as[FlowActionSetKey](flowActs(2)).getFlowKey should equal (
            new FlowKeyTunnelID().setTunnelID(bridge.getTunnelKey))
        flowActs.contains(FlowActions.output(portId4)) should be (true)
        flowActs.contains(FlowActions.output(portId5)) should be (true)
        flowActs.contains(FlowActions.output(tunnelId1)) should be (true)
        flowActs.contains(FlowActions.output(tunnelId2)) should be (true)
        verifyMacLearned("02:13:66:77:88:99", "port1")
        /*
         * MAC moved from port1 to port5
         * frame is going toward port4 (the learned MAC from verifyMacLearned)
         */
        inputPort = "port5"
        ethPkt = Packets.udp(
                     MAC.fromString("02:13:66:77:88:99"),
                     MAC.fromString("0a:fe:88:70:33:ab"),
                     IntIPv4.fromString("10.0.1.10"),
                     IntIPv4.fromString("10.0.1.11"),
                     10, 11, "My UDP packet".getBytes)
        addFlowMsg = injectOnePacket(ethPkt, inputPort, true)
        addFlowMsg.f should not be null
        var expectedPort = getMaterializedPort("port4")
        flowActs = addFlowMsg.f.getActions
        flowActs should have size(1)
        as[FlowActionOutput](flowActs(0)).getPortNumber() should equal (portId4)
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
    private def verifyMacLearned(learnedMac : String, expectedPortName : String) = {
        val ethPkt = Packets.udp(
                MAC.fromString("0a:fe:88:70:33:ab"),
                MAC.fromString(learnedMac),
                IntIPv4.fromString("10.10.10.10"),
                IntIPv4.fromString("10.11.11.11"),
                10, 12, "Test UDP packet".getBytes)
        val addFlowMsg = injectOnePacket(ethPkt, "port4", false)
        val expectedPort = getMaterializedPort(expectedPortName)
        val flowActs = addFlowMsg.f.getActions
        flowActs should have size(1)
        dpController().underlyingActor.vifToLocalPortNumber(expectedPort.getId) match {
            case Some(portNo : Short) =>
                as[FlowActionOutput](flowActs(0)).getPortNumber should equal (portNo)
            case None => fail("Not able to find data port number for materialize Port " +
                              expectedPort.getId)
        }
    }
}
