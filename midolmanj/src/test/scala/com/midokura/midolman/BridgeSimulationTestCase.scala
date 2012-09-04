/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.midolman

import com.midokura.sdn.dp.{FlowMatches, FlowMatch, Packet}
import com.midokura.sdn.dp.flows.FlowKeys
import datapath.FlowKeyVrnPort
import org.apache.commons.configuration.HierarchicalConfiguration
import com.midokura.midonet.cluster.data.{Bridge => ClusterBridge, Host, Ports}
import com.midokura.midolman.DatapathController.PacketIn
import com.midokura.packets.{IntIPv4, MAC, Packets}
import com.midokura.sdn.flows.WildcardMatches
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import topology.VirtualTopologyActor.PortRequest

@RunWith(classOf[JUnitRunner])
class BridgeSimulationTestCase extends MidolmanTestCase {

    override protected def fillConfig(config: HierarchicalConfiguration) = {
        config.setProperty("datapath.max_flow_count", "10")
        super.fillConfig(config)
    }

    def testPacketInBridgeSimulation() {

        val host = new Host(hostId()).setName("myself")
        clusterDataClient().hostsCreate(hostId(), host)

        val bridge = new ClusterBridge().setName("test")
        bridge.setId(clusterDataClient().bridgesCreate(bridge))

        val vifPort1 =
            clusterDataClient().portsCreate(Ports.materializedBridgePort(bridge))
        val vifPort2 =
            clusterDataClient().portsCreate(Ports.materializedBridgePort(bridge))

        //simProbe().testActor ! PacketIn(dpPkt,
        //    WildcardMatches.fromFlowMatch(flowMatch))
        //val pktIn = simProbe().expectMsgType[PacketIn]

        clusterDataClient().hostsAddVrnPortMapping(hostId, vifPort1, "port1")
        clusterDataClient().hostsAddVrnPortMapping(hostId, vifPort2, "port2")

        initializeDatapath() should not be (null)

        flowProbe().expectMsgType[DatapathController.DatapathReady].datapath should not be (null)

        val portNo = dpController().underlyingActor.localPorts("port1")
            .getPortNo
        val ethPkt = Packets.udp(
                MAC.fromString("02:11:22:33:44:10"),
                MAC.fromString("02:11:22:33:44:11"),
                IntIPv4.fromString("10.0.1.10"),
                IntIPv4.fromString("10.0.1.11"),
                10, 11, "My UDP packet".getBytes)

        val flowMatch = FlowMatches.fromEthernetPacket(ethPkt)
            .addKey(new FlowKeyVrnPort(vifPort1))
        val dpPkt = new Packet()
            .setMatch(flowMatch)
            .setData(ethPkt.serialize())
        triggerPacketIn(dpPkt)

        val packetIn = requestOfType[PacketIn](dpProbe())

        packetIn should not be null
        packetIn.packet should not be null
        packetIn.wMatch should not be null

        val packetInMsg = requestOfType[PacketIn](simProbe())

        packetInMsg.wMatch should not be null
        packetInMsg.wMatch.getInputPortUUID should be (vifPort1)

        val ingressPortRequest = requestOfType[PortRequest](vtaProbe())
    }
}
