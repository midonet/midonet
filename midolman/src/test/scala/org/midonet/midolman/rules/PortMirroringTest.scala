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

import org.midonet.cluster.data.ports.BridgePort
import org.midonet.cluster.data.{Bridge => ClusterBridge, Chain}
import org.midonet.cluster.data.rules.{MirrorRule => ClusterMirrorRule}
import org.midonet.midolman.PacketWorkflow.AddVirtualWildcardFlow
import org.midonet.midolman.RuleTestHelper
import org.midonet.midolman.util.MidolmanSpec

@RunWith(classOf[JUnitRunner])
class PortMirroringTest extends MidolmanSpec with RuleTestHelper {
    var bridge: ClusterBridge = _
    var port1: BridgePort = _
    var port2: BridgePort = _
    var chain: Chain = _

    override def beforeTest(): Unit = {
        bridge = newBridge("mirror-bridge")
        port1 = newBridgePort(bridge)
        port2 = newBridgePort(bridge)
        materializePort(port1, hostId, "mirror-port1")
        materializePort(port2, hostId, "mirror-port2")

        chain = newInboundChainOnBridge("mirror-chain", bridge)
        fetchTopology(bridge, port1, port2, chain)
    }

    private def newMirrorRule(chain: Chain, condition: Condition,
                              portId: UUID, pos: Int): Unit = {
        val mirrorRule = new ClusterMirrorRule(condition)
            .setChainId(chain.getId)
            .setPortId(portId)
            .setPosition(pos)
        clusterDataClient.rulesCreate(mirrorRule)
        fetchDevice(chain)
    }

    feature("Port mirroring enabled by rule in chain") {
        scenario("A port mirroring") {
            val mirrorRule = newMirrorRule(
                chain, newCondition(), port1.getId, 1)

            val pktCtx = packetContextFor(makeFrame(1), port1.getId)
            val (simRes, _) = simulate(pktCtx)
            simRes should be (AddVirtualWildcardFlow)
        }
    }
}
