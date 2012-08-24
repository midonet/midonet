/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.midolman

import com.midokura.midolman.FlowController.AddWildcardFlow
import com.midokura.sdn.flows.{WildcardMatch, WildcardFlow}
import com.midokura.midonet.cluster.data.{Ports, Bridge => ClusterBridge}
import com.midokura.sdn.dp.flows.FlowActions
import datapath.FlowActionVrnPortOutput
import java.util.Arrays

class InstallWildcardFlowTestCase extends MidolmanTestCase {

    def testDatapathPacketIn() {

        val bridge = clusterDataClient()
            .bridgesCreate(new ClusterBridge().setName("test"))

        val inputPort =
            clusterDataClient().portsCreate(Ports.materializedBridgePort(bridge))
        val outputPort =
            clusterDataClient().portsCreate(Ports.materializedBridgePort(bridge))

        clusterDataClient().hostsAddVrnPortMapping(hostId, inputPort, "inputPort")
        clusterDataClient().hostsAddVrnPortMapping(hostId, outputPort, "outputPort")

        initializeDatapath() should not be (null)

        flowProbe().expectMsgType[DatapathController.DatapathReady].datapath should not be (null)

        val inputPortNo = dpController().underlyingActor
            .localPorts("inputPort").getPortNo

        val outputPortNo = dpController().underlyingActor
            .localPorts("outputPort").getPortNo

        val vrnPortOutput = new FlowActionVrnPortOutput(outputPort)
        val dpPortOutput = FlowActions.output(outputPortNo)

        val wildcardFlow = new WildcardFlow(
            Arrays.asList(vrnPortOutput),
            0, 0, 0,
            new WildcardMatch()
                .setInputPortUUID(inputPort), 0)

        dpProbe().testActor.tell(AddWildcardFlow(wildcardFlow, None, null, null))

        val addFlowMsg = flowProbe().expectMsgType[AddWildcardFlow]

        addFlowMsg should not be null
        addFlowMsg.packet should not be null
        addFlowMsg.flow should not be null
        addFlowMsg.flow.getMatch.getInputPortUUID should be(null)
        addFlowMsg.flow.getMatch.getInputPortNumber should be(inputPortNo)

        val actions = addFlowMsg.flow.getActions
        actions should not be null
        actions.contains(dpPortOutput) should be (true)
        actions.contains(vrnPortOutput) should be (false)
    }
}
