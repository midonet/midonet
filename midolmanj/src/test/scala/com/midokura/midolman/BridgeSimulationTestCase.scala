/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.midolman

import org.apache.commons.configuration.HierarchicalConfiguration
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import com.midokura.midolman.DatapathController.{TunnelChangeEvent, PacketIn}
import com.midokura.midolman.FlowController.{AddWildcardFlow, WildcardFlowAdded, InvalidateFlowsByTag}
import com.midokura.midonet.cluster.data.{Bridge => ClusterBridge}
import com.midokura.packets.{Ethernet, IntIPv4, MAC, Packets}
import com.midokura.midonet.cluster.data.zones.GreTunnelZoneHost
import akka.testkit.TestProbe
import com.midokura.midonet.cluster.data.ports.MaterializedBridgePort
import com.midokura.sdn.dp.flows.{FlowActions, FlowActionOutput, FlowKeyTunnelID, FlowActionSetKey}


@RunWith(classOf[JUnitRunner])
class BridgeSimulationTestCase extends MidolmanTestCase
    with VirtualConfigurationBuilders {
    var flowEventsProbe: TestProbe = null
    var tunnelEventsProbe: TestProbe = null
    var portOnHost1: MaterializedBridgePort = null
    var bridge: ClusterBridge = null
    var tunnelId1: Short = 0
    var tunnelId2: Short = 0

    override protected def fillConfig(config: HierarchicalConfiguration) = {
        config.setProperty("datapath.max_flow_count", "10")
        super.fillConfig(config)
    }

    override def beforeTest {
        val tunnelZone = greTunnelZone("default")

        val host1 = newHost("host1", hostId())
        val host2 = newHost("host2")
        val host3 = newHost("host3")

        bridge = newBridge("bridge")

        portOnHost1 = newPortOnBridge(bridge)
        val portOnHost2 = newPortOnBridge(bridge)
        val portOnHost3 = newPortOnBridge(bridge)

        materializePort(portOnHost1, host1, "port1")
        materializePort(portOnHost2, host2, "port2")
        materializePort(portOnHost3, host3, "port3")

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
        pktInMsg.wMatch.getInputPortUUID should be(portOnHost1.getId)

        flowProbe().expectMsgClass(classOf[InvalidateFlowsByTag])

        dpProbe().expectMsgClass(classOf[AddWildcardFlow])
        flowEventsProbe.expectMsgClass(classOf[WildcardFlowAdded])
        flowProbe().expectMsgClass(classOf[InvalidateFlowsByTag])
        val addFlowMsg = requestOfType[AddWildcardFlow](flowProbe())
        addFlowMsg.pktBytes should not be null
        Ethernet.deserialize(addFlowMsg.pktBytes) should equal(ethPkt)
        addFlowMsg.flow should not be null
        val flowActs = addFlowMsg.flow.getActions
        flowActs should have size(3)
        as[FlowActionSetKey](flowActs.get(0)).getFlowKey should equal (
            new FlowKeyTunnelID().setTunnelID(bridge.getTunnelKey))
        flowActs.contains(FlowActions.output(tunnelId1)) should be (true)
        flowActs.contains(FlowActions.output(tunnelId2)) should be (true)
    }
}
