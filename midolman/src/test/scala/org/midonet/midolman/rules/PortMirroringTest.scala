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

import org.midonet.midolman.PacketWorkflow.{Drop, AddVirtualWildcardFlow}
import org.midonet.midolman.{PacketWorkflow, PacketTestHelper}
import org.midonet.midolman.simulation.Bridge
import org.midonet.midolman.state.NatState.{NatKey, NatBinding}
import org.midonet.midolman.topology.VirtualTopologyActor
import org.midonet.midolman.util.MidolmanSpec
import org.midonet.packets.IPv4Addr
import org.midonet.packets.util.PacketBuilder._
import org.midonet.sdn.state.{ShardedFlowStateTable, FlowStateTransaction}

@RunWith(classOf[JUnitRunner])
class PortMirroringTest extends MidolmanSpec with PacketTestHelper {
    registerActors(VirtualTopologyActor -> (() => new VirtualTopologyActor))

    var bridgeId: UUID = _
    var portSrcId: UUID = _
    var portDstId: UUID = _
    var chain: UUID = _
    var workflow: PacketWorkflow = _

    val inPortNum = 1
    val outPortNum = 2

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

        workflow = packetWorkflow(
            Map(inPortNum -> portSrcId,
                outPortNum -> portDstId)).underlyingActor
    }

    feature("Port mirroring enabled by rule in chain") {
        scenario("A mirror rule should be translated appropriately") {
            val mirrorRule = newMirrorRuleOnChain(
                chain, 1, newCondition(), portDstId)

            val context = packetContextFor(makeFrame(1), portSrcId)
            val simResult = workflow.runSimulation(context)
            simResult should be (AddVirtualWildcardFlow)
            workflow.processSimulationResult(context, simResult)
            // Output to the bridge and the port
            context.flowActions.size should be (2)
        }

        scenario("""A mirror rule preceded by a drop rule should not be
                   |translated
                 """.stripMargin.replaceAll("\n", " ")) {
            val dropRule = newLiteralRuleOnChain(
                chain, 1, newCondition(), RuleResult.Action.DROP)
            val mirrorRule = newMirrorRuleOnChain(
                chain, 2, newCondition(), portDstId)

            val context = packetContextFor(makeFrame(1), portSrcId)
            val simResult = workflow.runSimulation(context)
            simResult should be (Drop)
            workflow.processSimulationResult(context, simResult)
            context.flowActions.size should be (0)
        }

        scenario(""""A mirror rule succeeded by a drop rule should be
                   |translated
                 """.stripMargin.replaceAll("\n", "")) {
            val mirrorRule = newMirrorRuleOnChain(
                chain, 1, newCondition(), portDstId)
            val dropRule = newLiteralRuleOnChain(
                chain, 2, newCondition(), RuleResult.Action.DROP)

            val context = packetContextFor(makeFrame(1), portSrcId)
            val simResult = workflow.runSimulation(context)
            simResult should be (AddVirtualWildcardFlow)
            workflow.processSimulationResult(context, simResult)
            context.flowActions.size should be (2)
        }

        scenario("""A mirror rule preceded by a ACCEPT NAT rule modifies the
                   |flow key should not be translated
                 """.stripMargin.replaceAll("\n", " ")) {
            val stateTable =
                new ShardedFlowStateTable[NatKey, NatBinding]().addShard()
            implicit val natTx = new FlowStateTransaction(stateTable)

            val dnat = new NatTarget(IPv4Addr.fromString("192.168.0.2"), 1, 2)
            val natRule = newForwardNatRuleOnChain(chain, 1, newCondition(),
                RuleResult.Action.ACCEPT, Set(dnat), isDnat = true)
            val mirrorRule = newMirrorRuleOnChain(
                chain, 2, newCondition(), portDstId)

            val context = packetContextFor(makeFrame(1), portSrcId)
            val simResult = workflow.runSimulation(context)
            simResult should be (AddVirtualWildcardFlow)
            workflow.processSimulationResult(context, simResult)
            context.flowActions.size should be (2)
        }

        scenario("""A mirror rule preceded by a CONTINUE NAT rule modifies the
                   |flow key should be translated"
                 """".stripMargin.replaceAll("\n", " ")) {
            val stateTable =
                new ShardedFlowStateTable[NatKey, NatBinding]().addShard()
            implicit val natTx = new FlowStateTransaction(stateTable)

            val dnat = new NatTarget(IPv4Addr.fromString("192.168.0.2"), 1, 2)
            val natRule = newForwardNatRuleOnChain(chain, 1, newCondition(),
                RuleResult.Action.CONTINUE, Set(dnat), isDnat = true)
            val mirrorRule = newMirrorRuleOnChain(
                chain, 2, newCondition(), portDstId)

            val context = packetContextFor(makeFrame(1), portSrcId)
            val simResult = workflow.runSimulation(context)
            simResult should be (AddVirtualWildcardFlow)
            workflow.processSimulationResult(context, simResult)
            context.flowActions.size should be (3)
        }

        scenario("""A mirror rule should be applied in another chain jumped
                   |into
                 """".stripMargin.replaceAll("\n", " ")) {
            val jumpChainId = newChain("jumped-chain")
            fetchChains(jumpChainId)

            val mirrorRuleOnJumpChain = newMirrorRuleOnChain(
                jumpChainId, 1, newCondition(), portDstId)
            val returnRule = newLiteralRuleOnChain(
                jumpChainId, 2, newCondition(), RuleResult.Action.RETURN)

            val jumpRule = newJumpRuleOnChain(
                chain, 1, newCondition(), jumpChainId)

            val context = packetContextFor(makeFrame(1), portSrcId)
            val simResult = workflow.runSimulation(context)
            simResult should be (AddVirtualWildcardFlow)
            workflow.processSimulationResult(context, simResult)
            context.flowActions.size should be (2)
        }
    }
}
