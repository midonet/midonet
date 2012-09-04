/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.midolman

import com.midokura.sdn.dp.{FlowMatch, Packet}
import com.midokura.midolman.DatapathController.PacketIn
import com.midokura.sdn.dp.flows.FlowKeys
import org.apache.commons.configuration.HierarchicalConfiguration
import com.midokura.midonet.cluster.data.{Bridge => ClusterBridge, Host, Ports}
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import com.midokura.packets.{IntIPv4, MAC, Packets}

@RunWith(classOf[JUnitRunner])
class PacketInWorkflowTestCase extends MidolmanTestCase {

    override protected def fillConfig(config: HierarchicalConfiguration) = {
        config.setProperty("datapath.max_flow_count", "10")
        super.fillConfig(config)
    }

    def testDatapathPacketIn() {
        val host = new Host(hostId()).setName("myself")
        clusterDataClient().hostsCreate(hostId(), host)

        val bridge = new ClusterBridge().setName("test")
        bridge.setId(clusterDataClient().bridgesCreate(bridge))

        val vifPort = Ports.materializedBridgePort(bridge)
        vifPort.setId(clusterDataClient().portsCreate(vifPort))

        clusterDataClient().hostsAddVrnPortMapping(hostId, vifPort.getId, "port")

        initializeDatapath() should not be (null)

        flowProbe().expectMsgType[DatapathController.DatapathReady].datapath should not be (null)

        val portNo = dpController().underlyingActor.localPorts("port").getPortNo
        triggerPacketIn(
            new Packet()
                .setData(
                    Packets.udp(
                        MAC.fromString("10:10:10:10:10:10"),
                        MAC.fromString("10:10:10:10:10:11"),
                        IntIPv4.fromString("192.168.100.1"),
                        IntIPv4.fromString("192.168.200.1"),
                        100, 100, new Array[Byte](0)
                    ).serialize())
                .setMatch(new FlowMatch().addKey(FlowKeys.inPort(portNo))))

        val packetIn = dpProbe().expectMsgType[PacketIn]

        packetIn should not be null
        packetIn.packet should not be null
        packetIn.wMatch should not be null

        val packetInMsg = simProbe().expectMsgType[PacketIn]

        packetInMsg.wMatch should not be null
        packetInMsg.wMatch.getInputPortUUID should be (vifPort.getId)
    }

}
