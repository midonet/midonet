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

import scala.collection.JavaConverters._

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import org.midonet.midolman.PacketWorkflow.{Drop, AddVirtualWildcardFlow}
import org.midonet.midolman.{PacketWorkflow, PacketTestHelper}
import org.midonet.midolman.simulation.Bridge
import org.midonet.midolman.state.NatState.{NatKey, NatBinding}
import org.midonet.midolman.topology.VirtualTopologyActor
import org.midonet.midolman.util.MidolmanSpec
import org.midonet.odp.flows.{FlowKeys, FlowActions}
import org.midonet.packets.IPv4Addr
import org.midonet.packets.util.PacketBuilder._
import org.midonet.sdn.state.{ShardedFlowStateTable, FlowStateTransaction}

@RunWith(classOf[JUnitRunner])
class PortMirroringTest extends MidolmanSpec with PacketTestHelper {
    registerActors(VirtualTopologyActor -> (() => new VirtualTopologyActor))

    var bridgeId: UUID = _
    var portSrcId: UUID = _
    var portDstId: UUID = _
    var chainId: UUID = _
    var workflow: PacketWorkflow = _

    val inPortNum = 1
    val outPortNum = 2

    override def beforeTest(): Unit = {
        bridgeId = newBridge("mirror-bridge")
        portSrcId = newBridgePort(bridgeId)
        portDstId = newBridgePort(bridgeId)
        materializePort(portSrcId, hostId, "mirror-src-port")
        materializePort(portDstId, hostId, "mirror-dst-port")

        chainId = newInboundChainOnBridge("mirror-chain", bridgeId)

        fetchChains(chainId)
        fetchPorts(portSrcId, portDstId)
        fetchDevice[Bridge](bridgeId)

        workflow = packetWorkflow(
            Map(inPortNum -> portSrcId,
                outPortNum -> portDstId)).underlyingActor
    }

    feature("Port mirroring enabled by rule in chain") {
        scenario("A mirror rule should be translated appropriately") {
            assume(useNewStorageStack)

            val mirrorRule = newMirrorRuleOnChain(
                chainId, 1, newCondition(), portDstId)

            val context = packetContextFor(makeFrame(1), portSrcId)
            val simResult = workflow.runSimulation(context)
            simResult should be (AddVirtualWildcardFlow)
            workflow.processSimulationResult(context, simResult)
            context.flowActions should have size 2
            context.flowActions.asScala should equal (
                Array(FlowActions.output(outPortNum),  // Mirror
                    FlowActions.output(outPortNum)))   // Output to the bridge
        }

        scenario("A mirror rule preceded by a drop rule should not be translated") {
            assume(useNewStorageStack)

            val dropRule = newLiteralRuleOnChain(
                chainId, 1, newCondition(), RuleResult.Action.DROP)
            val mirrorRule = newMirrorRuleOnChain(
                chainId, 2, newCondition(), portDstId)
            fetchChains(chainId)

            val context = packetContextFor(makeFrame(1), portSrcId)
            val simResult = workflow.runSimulation(context)
            simResult should be (Drop)
            workflow.processSimulationResult(context, simResult)
            context.flowActions.asScala should equal (Array())
        }

        scenario("A mirror rule succeeded by a drop rule should be translated") {
            assume(useNewStorageStack)

            val mirrorRule = newMirrorRuleOnChain(
                chainId, 1, newCondition(), portDstId)
            val dropRule = newLiteralRuleOnChain(
                chainId, 2, newCondition(), RuleResult.Action.DROP)
            fetchChains(chainId)

            val context = packetContextFor(makeFrame(1), portSrcId)
            val simResult = workflow.runSimulation(context)
            // TODO(tfukushima): AddVirtualWildcardFlow is more appropriate?
            simResult should be (Drop)
            workflow.processSimulationResult(context, simResult)
            context.flowActions.asScala should equal (
                Array(FlowActions.output(outPortNum)))
        }

        scenario("A mirror rule succeeded by a accept rule should be translated") {
            assume(useNewStorageStack)

            val mirrorRule = newMirrorRuleOnChain(
                chainId, 1, newCondition(), portDstId)
            val dropRule = newLiteralRuleOnChain(
                chainId, 2, newCondition(), RuleResult.Action.ACCEPT)

            val context = packetContextFor(makeFrame(1), portSrcId)
            val simResult = workflow.runSimulation(context)
            simResult should be (AddVirtualWildcardFlow)
            workflow.processSimulationResult(context, simResult)
            context.flowActions.asScala should equal (
                Array(FlowActions.output(outPortNum),  // Mirror
                    FlowActions.output(outPortNum)))   // Output to the bridge
        }

        scenario("A mirror rule preceded by a ACCEPT NAT rule modifies the flow key should not be translated") {
            assume(useNewStorageStack)

            val st = new ShardedFlowStateTable[NatKey, NatBinding]().addShard()
            implicit val natTx = new FlowStateTransaction(st)

            val dnat = new NatTarget(IPv4Addr.fromString("192.168.0.2"), 1, 2)
            val natRule = newForwardNatRuleOnChain(chainId, 1, newCondition(),
                RuleResult.Action.ACCEPT, Set(dnat), isDnat = true)
            val mirrorRule = newMirrorRuleOnChain(
                chainId, 2, newCondition(), portDstId)

            val context = packetContextFor(makeFrame(1), portSrcId)
            val simResult = workflow.runSimulation(context)
            simResult should be (AddVirtualWildcardFlow)
            workflow.processSimulationResult(context, simResult)
            context.flowActions.asScala should equal (
                Array(FlowActions.setKey(FlowKeys.udp(10101, 2)),
                    FlowActions.output(outPortNum)))
        }

        scenario("A mirror rule preceded by a CONTINUE NAT rule modifies the flow key should be translated") {
            assume(useNewStorageStack)

            val st = new ShardedFlowStateTable[NatKey, NatBinding]().addShard()
            implicit val natTx = new FlowStateTransaction(st)

            val dnat = new NatTarget(IPv4Addr.fromString("192.168.0.2"), 1, 2)
            val natRule = newForwardNatRuleOnChain(chainId, 1, newCondition(),
                RuleResult.Action.CONTINUE, Set(dnat), isDnat = true)
            val mirrorRule = newMirrorRuleOnChain(
                chainId, 2, newCondition(), portDstId)

            val context = packetContextFor(makeFrame(1), portSrcId)
            val simResult = workflow.runSimulation(context)
            simResult should be (AddVirtualWildcardFlow)
            workflow.processSimulationResult(context, simResult)
            context.flowActions.asScala should equal (
                Array(FlowActions.setKey(FlowKeys.udp(10101, 2)),
                    FlowActions.output(outPortNum),
                    FlowActions.output(outPortNum)))
        }

        scenario("A mirror rule preceded by multiple CONTINUE NAT rules should translate them") {
            assume(useNewStorageStack)

            val st = new ShardedFlowStateTable[NatKey, NatBinding]().addShard()
            implicit val natTx = new FlowStateTransaction(st)

            val dnat1 = new NatTarget(IPv4Addr.fromString("192.168.0.2"), 1, 2)
            val dnatRule1 = newForwardNatRuleOnChain(chainId, 1, newCondition(),
                RuleResult.Action.CONTINUE, Set(dnat1), isDnat = true)
            val dnat2 = new NatTarget(IPv4Addr.fromString("192.168.0.2"), 2, 3)
            val dnatRule2 = newForwardNatRuleOnChain(chainId, 2, newCondition(),
                    RuleResult.Action.CONTINUE, Set(dnat2), isDnat = true)
            val mirrorRuleOnJumpChain = newMirrorRuleOnChain(
                chainId, 3, newCondition(), portDstId)
            fetchChains(chainId)

            val context = packetContextFor(makeFrame(1), portSrcId)
            val simResult = workflow.runSimulation(context)
            simResult should be (AddVirtualWildcardFlow)
            workflow.processSimulationResult(context, simResult)
            context.flowActions.asScala should equal (
                Array(FlowActions.setKey(FlowKeys.udp(10101, 3)),
                    FlowActions.output(outPortNum),
                    FlowActions.output(outPortNum)))
        }

        scenario("A mirror rule should be applied in another chain jumped into") {
            assume(useNewStorageStack)

            val jumpChainId = newChain("jumped-chain")
            fetchChains(jumpChainId)

            val mirrorRuleOnJumpChain = newMirrorRuleOnChain(
                jumpChainId, 1, newCondition(), portDstId)
            val returnRule = newLiteralRuleOnChain(
                jumpChainId, 2, newCondition(), RuleResult.Action.RETURN)
            fetchChains(jumpChainId)

            val jumpRule = newJumpRuleOnChain(
                chainId, 1, newCondition(), jumpChainId)
            fetchChains(chainId)

            val context = packetContextFor(makeFrame(1), portSrcId)
            val simResult = workflow.runSimulation(context)
            simResult should be (AddVirtualWildcardFlow)
            workflow.processSimulationResult(context, simResult)
            context.flowActions.asScala should equal (
                Array(FlowActions.output(outPortNum),
                    FlowActions.output(outPortNum)))
        }
    }
}
