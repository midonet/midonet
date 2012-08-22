/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.midolman

import com.midokura.sdn.dp.{FlowMatch, Packet}
import com.midokura.midolman.DatapathController.PacketIn
import java.util.UUID
import com.midokura.sdn.dp.flows.{FlowKey, FlowKeys}
import org.apache.commons.configuration.HierarchicalConfiguration

class PacketInWorkflowTestCase extends MidolmanTestCase {

    override protected def fillConfig(config: HierarchicalConfiguration) = {
        config.setProperty("datapath.max_flow_count", "10")
        super.fillConfig(config)
    }

    def testDatapathPacketIn() {

        val vifPort = UUID.randomUUID()
        midoStore().setLocalVrnPortMapping(hostId, vifPort, "port")

        initializeDatapath() should not be (null)

        flowProbe().expectMsgType[DatapathController.DatapathReady].datapath should not be (null)

        val portNo = dpController().underlyingActor.localPorts("port").getPortNo
        triggerPacketIn(
            new Packet().setMatch(
                new FlowMatch()
                    .addKey(FlowKeys.inPort(portNo))))

        val packetIn = dpProbe().expectMsgType[PacketIn]

        packetIn should not be null
        packetIn.packet should not be null
        packetIn.wildcardMatch should not be null

        val packetInMsg = simProbe().expectMsgType[PacketIn]

        packetInMsg.wildcardMatch should not be null
        packetInMsg.wildcardMatch.getInputPortUUID should be (vifPort)
    }
}
