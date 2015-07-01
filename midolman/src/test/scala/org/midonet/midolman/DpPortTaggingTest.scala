/*
 * Copyright 2015 Midokura SARL
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

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import java.util.UUID

import org.midonet.cluster.data.ports.BridgePort
import org.midonet.midolman.PacketWorkflow.Drop
import org.midonet.midolman.simulation.Bridge
import org.midonet.midolman.topology.VirtualTopologyActor
import org.midonet.midolman.util.MidolmanSpec
import org.midonet.packets._
import org.midonet.packets.util.PacketBuilder._
import org.midonet.sdn.flows.FlowTagger

@RunWith(classOf[JUnitRunner])
class DpPortTaggingTest extends MidolmanSpec {
    var bridge: UUID = _
    var inPort: BridgePort = _
    val inPortNumber = 1
    var outPort: BridgePort = _
    val outPortNumber = 2

    registerActors(VirtualTopologyActor -> (() => new VirtualTopologyActor))

    override def beforeTest(): Unit = {
        bridge = newBridge("bridge")
        inPort = newBridgePort(bridge)
        outPort = newBridgePort(bridge)

        materializePort(outPort, hostId, "outPort")

        fetchTopology(inPort, outPort)
        fetchDevice[Bridge](bridge)
    }

    scenario ("Flow is tagged with input and output DP ports") {
        val context = packetContextFor({ eth src MAC.random() dst MAC.random() },
                                       inPort.getId)
        context.origMatch.setInputPortNumber(inPortNumber)
        context.wcmatch.setInputPortNumber(inPortNumber)
        workflow.start(context) should not be Drop
        context should be (taggedWith(FlowTagger.tagForDpPort(inPortNumber),
                                      FlowTagger.tagForDpPort(outPortNumber)))
    }

    def workflow = packetWorkflow(Map(inPortNumber -> inPort.getId,
                                      outPortNumber -> outPort.getId)).underlyingActor
}
