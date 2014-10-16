/*
 * Copyright 2014 Midokura SARL
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.midonet.midolman

import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import org.midonet.cluster.data.{Bridge => ClusterBridge, Ports}
import org.midonet.cluster.data.host.Host
import org.midonet.midolman.FlowController.{WildcardFlowAdded, AddWildcardFlow}
import org.midonet.midolman.PacketWorkflow.AddVirtualWildcardFlow
import org.midonet.midolman.topology.LocalPortActive
import org.midonet.midolman.util.MidolmanTestCase
import org.midonet.sdn.flows.VirtualActions.FlowActionOutputToVrnPort
import org.midonet.sdn.flows.{WildcardMatch, WildcardFlow}
import org.midonet.odp.flows.FlowActions.output

@Category(Array(classOf[SimulationTests]))
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

        materializePort(inputPort, host, "inputPort")
        materializePort(outputPort, host, "outputPort")

        drainProbes()
        initializeDatapath() should not be (null)
        flowProbe().expectMsgType[DatapathController.DatapathReady].datapath should not be (null)
        portsProbe.expectMsgClass(classOf[LocalPortActive])
        portsProbe.expectMsgClass(classOf[LocalPortActive])

        val inputPortNo = getPortNumber("inputPort")
        val outputPortNo = getPortNumber("outputPort")

        val vrnPortOutput = new FlowActionOutputToVrnPort(outputPort.getId)
        val dpPortOutput = output(outputPortNo)

        fishForRequestOfType[AddWildcardFlow](flowProbe())
        fishForRequestOfType[AddWildcardFlow](flowProbe())
        drainProbes()

        addVirtualWildcardFlow(inputPort.getId, vrnPortOutput)

        val addFlowMsg = fishForRequestOfType[WildcardFlowAdded](wflowAddedProbe)

        addFlowMsg should not be null
        addFlowMsg.f should not be null
        addFlowMsg.f.getMatch.getInputPortNumber should be(inputPortNo)

        val actions = addFlowMsg.f.getActions
        actions should not be null
        actions.contains(dpPortOutput) should be (true)
        actions.contains(vrnPortOutput) should be (false)
    }
}
