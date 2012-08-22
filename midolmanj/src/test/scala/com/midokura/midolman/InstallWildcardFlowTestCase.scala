/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.midolman

import java.util.UUID
import com.midokura.midolman.DatapathController.PacketIn

class InstallWildcardFlowTestCase extends MidolmanTestCase {

    def testDatapathPacketIn() {

        val vifPort = UUID.randomUUID()
        midoStore().setLocalVrnPortMapping(hostId, vifPort, "port")

        initializeDatapath() should not be (null)

        flowProbe().expectMsgType[DatapathController.DatapathReady].datapath should not be (null)

        val portNo = dpController().underlyingActor.localPorts("port").getPortNo
//        triggerPacketIn(
//            new Packet()
//                .setMatch(
//                    new FlowMatch()
//                        .addKey(FlowKeys.inPort(portNo))))

        val addFlow = dpProbe().expectMsgType[FlowController.AddWildcardFlow]

        addFlow should not be null
        addFlow.packet should not be null
        addFlow.wFlow should not be null

        val packetInMsg = simProbe().expectMsgType[PacketIn]

        packetInMsg.wildcardMatch should not be null
        packetInMsg.wildcardMatch.getInputPortUUID should be(vifPort)
    }

}
