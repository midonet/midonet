/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.midolman

import java.util.Arrays

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import com.midokura.midolman.FlowController.AddWildcardFlow
import com.midokura.midolman.datapath.FlowActionOutputToVrnPort
import com.midokura.midolman.topology.LocalPortActive
import com.midokura.midonet.cluster.data.{Bridge => ClusterBridge, Ports}
import com.midokura.midonet.cluster.data.host.Host
import com.midokura.odp.flows.FlowActions
import com.midokura.sdn.flows.{WildcardMatch, WildcardFlow}


@RunWith(classOf[JUnitRunner])
class InstallWildcardFlowTestCase extends MidolmanTestCase {

    def testInstallFlowForLocalPort() {

        val host = new Host(hostId()).setName("myself")
        clusterDataClient().hostsCreate(hostId(), host)

        val bridge = new ClusterBridge().setName("test")
        bridge.setId(clusterDataClient().bridgesCreate(bridge))

        val inputPort = Ports.materializedBridgePort(bridge)
        inputPort.setId(clusterDataClient().portsCreate(inputPort))

        val outputPort = Ports.materializedBridgePort(bridge)
        outputPort.setId(clusterDataClient().portsCreate(outputPort))

        val portEventsProbe = newProbe()
        actors().eventStream.subscribe(portEventsProbe.ref, classOf[LocalPortActive])

        materializePort(inputPort, host, "inputPort")
        materializePort(outputPort, host, "outputPort")

        drainProbe(flowProbe())
        initializeDatapath() should not be (null)
        flowProbe().expectMsgType[DatapathController.DatapathReady].datapath should not be (null)
        portEventsProbe.expectMsgClass(classOf[LocalPortActive])
        portEventsProbe.expectMsgClass(classOf[LocalPortActive])

        val inputPortNo = dpController().underlyingActor
            .ifaceNameToDpPort("inputPort").getPortNo

        val outputPortNo = dpController().underlyingActor
            .ifaceNameToDpPort("outputPort").getPortNo

        val vrnPortOutput = new FlowActionOutputToVrnPort(outputPort.getId)
        val dpPortOutput = FlowActions.output(outputPortNo)

        val wildcardMatch = new WildcardMatch()
            .setInputPortUUID(inputPort.getId)

        val wildcardFlow = new WildcardFlow()
            .addAction(vrnPortOutput)
            .setMatch(wildcardMatch)

        fishForRequestOfType[AddWildcardFlow](flowProbe())
        fishForRequestOfType[AddWildcardFlow](flowProbe())
        drainProbe(flowProbe())

        dpProbe().testActor.tell(AddWildcardFlow(
            wildcardFlow, None, "My packet".getBytes, null, null))

        val addFlowMsg = fishForRequestOfType[AddWildcardFlow](flowProbe())

        addFlowMsg should not be null
        addFlowMsg.pktBytes should not be null
        addFlowMsg.flow should not be null
        addFlowMsg.flow.getMatch.getInputPortUUID should be(null)
        addFlowMsg.flow.getMatch.getInputPortNumber should be(inputPortNo)

        val actions = addFlowMsg.flow.getActions
        actions should not be null
        actions.contains(dpPortOutput) should be (true)
        actions.contains(vrnPortOutput) should be (false)
    }
}
