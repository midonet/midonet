/*
* Copyright 2012 Midokura Europe SARL
*/
package org.midonet.midolman

import java.util.Arrays

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import org.midonet.midolman.FlowController.{WildcardFlowAdded, AddWildcardFlow}
import org.midonet.midolman.PacketWorkflow.AddVirtualWildcardFlow
import org.midonet.midolman.datapath.FlowActionOutputToVrnPort
import org.midonet.midolman.topology.LocalPortActive
import org.midonet.cluster.data.{Bridge => ClusterBridge, Ports}
import org.midonet.cluster.data.host.Host
import org.midonet.odp.flows.FlowActions
import org.midonet.sdn.flows.{WildcardMatch, WildcardFlow}


@RunWith(classOf[JUnitRunner])
class InstallWildcardFlowTestCase extends MidolmanTestCase {

    def testInstallFlowForLocalPort() {

        val host = new Host(hostId()).setName("myself")
        clusterDataClient().hostsCreate(hostId(), host)

        val bridge = new ClusterBridge().setName("test")
        bridge.setId(clusterDataClient().bridgesCreate(bridge))

        val inputPort = Ports.bridgePort(bridge)
        inputPort.setId(clusterDataClient().portsCreate(inputPort))

        val outputPort = Ports.bridgePort(bridge)
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

        val inputPortNo = getPortNumber("inputPort")
        val outputPortNo = getPortNumber("outputPort")

        val vrnPortOutput = new FlowActionOutputToVrnPort(outputPort.getId)
        val dpPortOutput = FlowActions.output(outputPortNo)

        val wildcardMatch = new WildcardMatch()
            .setInputPortUUID(inputPort.getId)

        val wildcardFlow = WildcardFlow(
            actions = List(vrnPortOutput),
            wcmatch = wildcardMatch)

        fishForRequestOfType[AddWildcardFlow](flowProbe())
        fishForRequestOfType[AddWildcardFlow](flowProbe())
        drainProbe(flowProbe())
        drainProbe(wflowAddedProbe)

        dpProbe().testActor.tell(AddVirtualWildcardFlow(
            wildcardFlow, Set.empty, Set.empty))

        val addFlowMsg = fishForRequestOfType[WildcardFlowAdded](wflowAddedProbe)

        addFlowMsg should not be null
        addFlowMsg.f should not be null
        addFlowMsg.f.getMatch.getInputPortUUID should be(null)
        addFlowMsg.f.getMatch.getInputPortNumber should be(inputPortNo)

        val actions = addFlowMsg.f.getActions
        actions should not be null
        actions.contains(dpPortOutput) should be (true)
        actions.contains(vrnPortOutput) should be (false)
    }
}
