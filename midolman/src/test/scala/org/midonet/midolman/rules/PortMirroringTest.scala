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

package org.midonet.midolman.rules

import java.util.UUID

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import org.midonet.midolman.PacketWorkflow.AddVirtualWildcardFlow
import org.midonet.midolman.PacketTestHelper
import org.midonet.midolman.simulation.Bridge
import org.midonet.midolman.topology.VirtualTopologyActor
import org.midonet.midolman.util.MidolmanSpec
import org.midonet.packets.util.PacketBuilder._

@RunWith(classOf[JUnitRunner])
class PortMirroringTest extends MidolmanSpec with PacketTestHelper {
    registerActors(VirtualTopologyActor -> (() => new VirtualTopologyActor))

    var bridgeId: UUID = _
    var portSrcId: UUID = _
    var portDstId: UUID = _
    var chain: UUID = _

    override def beforeTest(): Unit = {
        bridgeId = newBridge("mirror-bridge")
        portSrcId = newBridgePort(bridgeId)
        portDstId = newBridgePort(bridgeId)
        materializePort(portSrcId, hostId, "mirror-src-port")
        materializePort(portDstId, hostId, "mirror-dst-port")

        chain = newInboundChainOnBridge("mirror-chain", bridgeId)
        fetchChains(chain)
        fetchPorts(portSrcId, portDstId)
        fetchDevice[Bridge](bridgeId)
    }

    feature("Port mirroring enabled by rule in chain") {
        scenario("A port mirroring should do nothing for now") {
            val mirrorRule = newMirrorRuleOnChain(
                chain, 1, newCondition(), portDstId)

            val pktCtx = packetContextFor(makeFrame(1), portSrcId)
            val (simRes, _) = simulate(pktCtx)
            simRes should be (AddVirtualWildcardFlow)
        }
    }
}
