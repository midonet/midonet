/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.midolman

import org.apache.commons.configuration.HierarchicalConfiguration
import org.junit.runner.RunWith
import org.scalatest.BeforeAndAfterEach
import org.scalatest.junit.JUnitRunner
import java.util.concurrent.TimeUnit

import com.midokura.midolman.DatapathController.{TunnelChangeEvent, PacketIn}
import com.midokura.midolman.FlowController.{AddWildcardFlow, WildcardFlowAdded, InvalidateFlowsByTag}
import com.midokura.midonet.cluster.data.{Bridge => ClusterBridge}
import com.midokura.packets.{Ethernet, IntIPv4, MAC, Packets}
import com.midokura.midonet.cluster.data.zones.GreTunnelZoneHost
import akka.util.Duration
import akka.testkit.TestProbe
import com.midokura.midonet.cluster.data.ports.MaterializedBridgePort
import com.midokura.sdn.dp.flows.{FlowActions, FlowActionOutput, FlowKeyTunnelID, FlowActionSetKey}
import com.midokura.packets.ARP
import com.midokura.packets.Ethernet


@RunWith(classOf[JUnitRunner])
class BridgeSimulationTestCase extends MidolmanTestCase with VirtualConfigurationBuilders 
        with BeforeAndAfterEach {
    private var flowEventsProbe: TestProbe = null
    private var tunnelEventsProbe: TestProbe = null
    private var port1OnHost1: MaterializedBridgePort = null
    private var port2OnHost1: MaterializedBridgePort = null
    private var bridge: ClusterBridge = null
    private var tunnelId1: Short = 0
    private var tunnelId2: Short = 0

    override protected def fillConfig(config: HierarchicalConfiguration) = {
        config.setProperty("datapath.max_flow_count", "10")
        super.fillConfig(config)
    }

    override def beforeTest = {
        val tunnelZone = greTunnelZone("default")

        val host1 = newHost("host1", hostId())
        val host2 = newHost("host2")
        val host3 = newHost("host3")

        bridge = newBridge("bridge")

        port1OnHost1 = newPortOnBridge(bridge)
        port2OnHost1 = newPortOnBridge(bridge)
        val portOnHost2 = newPortOnBridge(bridge)
        val portOnHost3 = newPortOnBridge(bridge)

        materializePort(port1OnHost1, host1, "port1")
        materializePort(portOnHost2, host2, "port2")
        materializePort(portOnHost3, host3, "port3")
        materializePort(port2OnHost1, host1, "port4")

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

        flowEventsProbe = newProbe()
        tunnelEventsProbe = newProbe()
        actors().eventStream.subscribe(tunnelEventsProbe.ref, classOf[TunnelChangeEvent])
        actors().eventStream.subscribe(flowEventsProbe.ref, classOf[WildcardFlowAdded])

        initializeDatapath() should not be (null)

        flowProbe().expectMsgType[DatapathController.DatapathReady].datapath should not be (null)

        tunnelId1 = tunnelEventsProbe.expectMsgClass(classOf[TunnelChangeEvent]).portOption.get
        tunnelId2 = tunnelEventsProbe.expectMsgClass(classOf[TunnelChangeEvent]).portOption.get
        drainProbes()
    }

    override def beforeEach() {
        // TODO: reset Bridge (flush MAC learning table at least)
    }

    def getAddWildcardFlowPartialFunction: PartialFunction[Any, Boolean] = {
        {
            case msg: AddWildcardFlow => true
            case _ => false
        }
    }


    def testPacketInBridgeSimulation() {
        val ethPkt = Packets.udp(
                MAC.fromString("02:11:22:33:44:10"),
                MAC.fromString("02:11:22:33:44:11"),
                IntIPv4.fromString("10.0.1.10"),
                IntIPv4.fromString("10.0.1.11"),
                10, 11, "My UDP packet".getBytes)
        triggerPacketIn("port1", ethPkt)

        dpProbe().expectMsgClass(classOf[PacketIn])

        val pktInMsg = simProbe().expectMsgClass(classOf[PacketIn])

        pktInMsg should not be null
        pktInMsg.pktBytes should not be null
        pktInMsg.wMatch should not be null
        pktInMsg.wMatch.getInputPortUUID should be(port1OnHost1.getId)

        //flowProbe().expectMsgClass(classOf[InvalidateFlowsByTag])

        dpProbe().expectMsgClass(classOf[AddWildcardFlow])
        flowEventsProbe.expectMsgClass(classOf[WildcardFlowAdded])
        //flowProbe().expectMsgClass(classOf[InvalidateFlowsByTag])
        //val addFlowMsg = requestOfType[AddWildcardFlow](flowProbe())
        val addFlowMsg = (flowProbe.fishForMessage(Duration(1, TimeUnit.SECONDS),
                                   "AddWildcardFlow")(getAddWildcardFlowPartialFunction)).asInstanceOf[AddWildcardFlow]
        addFlowMsg.pktBytes should not be null
        Ethernet.deserialize(addFlowMsg.pktBytes) should equal(ethPkt)
        addFlowMsg.flow should not be null
        val flowActs = addFlowMsg.flow.getActions
        flowActs should have size(4)
        as[FlowActionSetKey](flowActs.get(1)).getFlowKey should equal (
            new FlowKeyTunnelID().setTunnelID(bridge.getTunnelKey))
        flowActs.contains(FlowActions.output(tunnelId1)) should be (true)
        flowActs.contains(FlowActions.output(tunnelId2)) should be (true)
        verifyMacLearned("02:11:22:33:44:10", "port1")
    }

    def testBcastPktBridgeSim () {
        val inputPort = "port1"
        val ethPkt = Packets.udp(
                MAC.fromString("0a:fe:88:70:44:55"),
                MAC.fromString("ff:ff:ff:ff:ff:ff"),
                IntIPv4.fromString("10.10.10.10"),
                IntIPv4.fromString("10.11.11.11"),
                10, 12, "Test UDP packet".getBytes)
        val addFlowMsg = injectOnePacket(ethPkt, inputPort, false, false)
        val flowActs = addFlowMsg.flow.getActions
        flowActs should have size(4)
        //First Action: flood to local port [port4]
        dpController().underlyingActor.vifToLocalPortNumber(port2OnHost1.getId) match {
            case Some(portNo : Short) =>
                as [FlowActionOutput](flowActs.get(0)).getPortNumber() should equal (portNo)
            case None => fail("Not able to find data port number for materialize Port 4")
        }
        //Second Action: set tunnel ID
        as[FlowActionSetKey](flowActs.get(1)).getFlowKey should equal (
            new FlowKeyTunnelID().setTunnelID(bridge.getTunnelKey))
        //Next two actions: flooding out to tunnel with ID 1 and 2
        flowActs.contains(FlowActions.output(tunnelId1)) should be (true)
        flowActs.contains(FlowActions.output(tunnelId2)) should be (true)
        //Our source MAC should also be learned
        //verifyMacLearned("0a:fe:88:70:44:55", inputPort)
    }

    def testBcastArpBridgeSim () {
        val inputPort = "port1"
        val ethPkt = Packets.arpRequest(
                MAC.fromString("0a:fe:88:90:22:33"),
                IntIPv4.fromString("10.10.10.11"),
                IntIPv4.fromString("10.11.11.10"))
        val addFlowMsg = injectOnePacket(ethPkt, inputPort, false, false)
        val flowActs = addFlowMsg.flow.getActions
        flowActs should have size(4)
        //First Action: flood to local port [port4]
        dpController().underlyingActor.vifToLocalPortNumber(port2OnHost1.getId) match {
            case Some(portNo : Short) =>
                as [FlowActionOutput](flowActs.get(0)).getPortNumber() should equal (portNo)
            case None => fail("Not able to find data port number for materialize Port 4")
        }
        //Second Action: set tunnel ID
        as[FlowActionSetKey](flowActs.get(1)).getFlowKey should equal (
            new FlowKeyTunnelID().setTunnelID(bridge.getTunnelKey))
        //Next two actions: flooding out to tunnel with ID 1 and 2
        flowActs.contains(FlowActions.output(tunnelId1)) should be (true)
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
        val addFlowMsg = injectOnePacket(ethPkt, inputPort, false, false)
        val flowActs = addFlowMsg.flow.getActions
        flowActs should have size(4)
        //First Action: flood to local port [port4]
        dpController().underlyingActor.vifToLocalPortNumber(port2OnHost1.getId) match {
            case Some(portNo : Short) =>
                as [FlowActionOutput](flowActs.get(0)).getPortNumber() should equal (portNo)
            case None => fail("Not able to find data port number for materialize Port 4")
        }
        //Second Action: set tunnel ID
        as[FlowActionSetKey](flowActs.get(1)).getFlowKey should equal (
            new FlowKeyTunnelID().setTunnelID(bridge.getTunnelKey))
        //Next two actions: flooding out to tunnel with ID 1 and 2
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
        val addFlowMsg = injectOnePacket(ethPkt, inputPort, true, true)
        val flowActs = addFlowMsg.flow.getActions
        flowActs should have size(0)
    }

    private def injectOnePacket (ethPkt : Ethernet, ingressPortName : String, 
                                 isMacLearned : Boolean,
                                 isDropExpected: Boolean) : AddWildcardFlow = {

        triggerPacketIn(ingressPortName, ethPkt)

        val pktInMsg = simProbe().expectMsgClass(classOf[PacketIn])
        val ingressPort = getMaterializedPort(ingressPortName)

        pktInMsg should not be null
        pktInMsg.pktBytes should not be null
        pktInMsg.wMatch should not be null
        pktInMsg.wMatch.getInputPortUUID should be(ingressPort.getId)

        flowEventsProbe.expectMsgClass(classOf[WildcardFlowAdded])
        val addFlowMsg = (flowProbe.fishForMessage(Duration(1, TimeUnit.SECONDS),
                          "AddWildcardFlow")(getAddWildcardFlowPartialFunction)).
                          asInstanceOf[AddWildcardFlow]
        if (isDropExpected == false) {
            addFlowMsg.pktBytes should not be null
            Ethernet.deserialize(addFlowMsg.pktBytes) should equal(ethPkt)
        }
        addFlowMsg
    }

    private def getMaterializedPort (portName : String) : MaterializedBridgePort = {
        portName match {
            case "port1" => port1OnHost1
            case "port4" => port2OnHost1
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
        val addFlowMsg = injectOnePacket(ethPkt, "port4", true, false)
        val expectedPort = getMaterializedPort(expectedPortName)
        val flowActs = addFlowMsg.flow.getActions
        flowActs should have size(1)
        dpController().underlyingActor.vifToLocalPortNumber(expectedPort.getId) match {
            case Some(portNo : Short) =>
                as[FlowActionOutput](flowActs.get(0)).getPortNumber() should equal (portNo)
            case None => fail("Not able to find data port number for materialize Port " +
                              expectedPort.getId)
        }
    }
}
