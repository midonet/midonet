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

import java.util.{LinkedList, UUID}

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import org.midonet.cluster.data.rules.{TraceRule => TraceRuleData}
import org.midonet.midolman.PacketWorkflow.AddVirtualWildcardFlow
import org.midonet.midolman.PacketWorkflow.SimulationResult
import org.midonet.midolman.rules.Condition
import org.midonet.midolman.simulation.{Bridge, Coordinator, PacketContext}
import org.midonet.midolman.state.HappyGoLuckyLeaser
import org.midonet.midolman.state.TraceState.{TraceKey, TraceContext}
import org.midonet.midolman.topology._
import org.midonet.midolman.util.MidolmanSpec
import org.midonet.odp.{FlowMatches, Packet}
import org.midonet.packets.Ethernet
import org.midonet.packets.util.EthBuilder
import org.midonet.packets.util.PacketBuilder._
import org.midonet.sdn.state.ShardedFlowStateTable
import org.midonet.sdn.state.FlowStateTransaction

@RunWith(classOf[JUnitRunner])
class FlowTracingTest extends MidolmanSpec {
    registerActors(VirtualTopologyActor -> (() => new VirtualTopologyActor))

    var bridge: UUID = _
    var port1: UUID = _
    var port2: UUID = _
    var chain: UUID = _

    val table = new ShardedFlowStateTable[TraceKey,TraceContext](clock)
        .addShard()
    implicit val traceTx = new FlowStateTransaction(table)

    override def beforeTest(): Unit = {
        bridge = newBridge("bridge0")
        port1 = newBridgePort(bridge)
        port2 = newBridgePort(bridge)
        materializePort(port1, hostId, "port1")
        materializePort(port2, hostId, "port2")

        chain = newInboundChainOnBridge("my-chain", bridge)
        fetchChains(chain)
        fetchPorts(port1, port2)
        fetchDevice[Bridge](bridge)
    }

    private def newTraceRule(requestId: UUID, chain: UUID,
                             condition: Condition, pos: Int) {
        val traceRule = new TraceRuleData(requestId, condition, Long.MaxValue)
            .setChainId(chain).setPosition(pos)
        clusterDataClient.rulesCreate(traceRule)
        fetchChains(chain)
    }

    private def makeFrame(tpDst: Short, tpSrc: Short = 10101) =
        { eth addr "00:02:03:04:05:06" -> "00:20:30:40:50:60" } <<
        { ip4 addr "192.168.0.1" --> "192.168.0.2" } <<
        { udp ports tpSrc ---> tpDst }

    implicit def ethBuilder2Packet(ethBuilder: EthBuilder[Ethernet]): Packet = {
        val frame: Ethernet = ethBuilder
        val flowMatch = FlowMatches.fromEthernetPacket(frame)
        val pkt = new Packet(frame, flowMatch)
        flowMatch.setInputPortNumber(42)
        pkt
    }

    def makePacket(variation: Short): Packet = makeFrame(variation)

    // override to avoid clearing the packet context
    def simulate(pktCtx: PacketContext)
            : (SimulationResult, PacketContext) = {
        pktCtx.initialize(NO_CONNTRACK, NO_NAT, HappyGoLuckyLeaser, traceTx)
        val r = force {
            new Coordinator(pktCtx) simulate()
        }
        (r, pktCtx)
    }

    feature("Tracing enabled by rule in chain") {
        scenario("tracing enabled by catchall rule") {
            val requestId = UUID.randomUUID
            newTraceRule(requestId, chain, newCondition(), 1)

            val pktCtx = packetContextFor(makeFrame(1), port1)
            pktCtx.tracingEnabled(requestId) should be (false)
            try {
                simulate(pktCtx)
                fail("Should have thrown an exception")
            } catch {
                case TraceRequiredException =>
                    pktCtx.tracingEnabled(requestId) should be (true)
                    pktCtx.log should be (PacketContext.traceLog)
            }
            val (simRes, _) = simulate(pktCtx)
            simRes should be (AddVirtualWildcardFlow)
        }

        scenario("tracing enabled by specific rule match") {
            val requestId = UUID.randomUUID
            newTraceRule(requestId, chain,
                         newCondition(tpDst = Some(500)), 1)

            val pktCtx = packetContextFor(makeFrame(1), port1)
            pktCtx.tracingEnabled(requestId) should be (false)
            simulate(pktCtx)._1 should be (AddVirtualWildcardFlow)
            pktCtx.tracingEnabled(requestId) should be (false)
            pktCtx.log should not be PacketContext.traceLog

            val pktCtx2 = packetContextFor(makeFrame(500), port1)
            pktCtx2.tracingEnabled(requestId) should be (false)
            try {
                simulate(pktCtx2)
                fail("Should have thrown an exception")
            } catch {
                case TraceRequiredException =>
                    pktCtx2.tracingEnabled(requestId) should be (true)
                    pktCtx2.log should be (PacketContext.traceLog)
            }
            simulate(pktCtx2)._1 should be (AddVirtualWildcardFlow)

            val pktCtx3 = packetContextFor(makeFrame(1000), port1)
            pktCtx3.tracingEnabled(requestId) should be (false)
            simulate(pktCtx3)._1 should be (AddVirtualWildcardFlow)
            pktCtx3.tracingEnabled(requestId) should be (false)
            pktCtx3.log should not be PacketContext.traceLog
        }

        scenario("Multiple rules matching on different things") {
            val requestId1 = UUID.randomUUID
            val requestId2 = UUID.randomUUID
            newTraceRule(requestId1, chain,
                         newCondition(tpDst = Some(500)), 1)
            newTraceRule(requestId2, chain,
                         newCondition(tpSrc = Some(1000)), 1)

            val pktCtx = packetContextFor(makeFrame(500), port1)
            pktCtx.tracingEnabled(requestId1) should be (false)
            pktCtx.tracingEnabled(requestId2) should be (false)
            try {
                simulate(pktCtx)
                fail("Should have thrown an exception")
            } catch {
                case TraceRequiredException =>
                    pktCtx.log should be (PacketContext.traceLog)
                    pktCtx.tracingEnabled(requestId1) should be (true)
                    pktCtx.tracingEnabled(requestId2) should be (false)
            }
            simulate(pktCtx)._1 should be (AddVirtualWildcardFlow)

            val pktCtx2 = packetContextFor(makeFrame(500, 1000), port1)
            pktCtx2.tracingEnabled(requestId1) should be (false)
            try {
                // should hit second rule
                simulate(pktCtx2)
                fail("Should have thrown an exception")
            } catch {
                case TraceRequiredException =>
                    pktCtx2.tracingEnabled(requestId2) should be (true)
                    pktCtx2.tracingEnabled(requestId1) should be (false)
                    pktCtx2.log should be (PacketContext.traceLog)
            }
            try {
                // should hit first rule
                simulate(pktCtx2)
                fail("Should have thrown an exception")
            } catch {
                case TraceRequiredException =>
                    pktCtx2.tracingEnabled(requestId2) should be (true)
                    pktCtx2.tracingEnabled(requestId1) should be (true)
                    pktCtx2.log should be (PacketContext.traceLog)
            }
            simulate(pktCtx2)._1 should be (AddVirtualWildcardFlow)
        }

        scenario("restart on trace requested exception") {
            val requestId = UUID.randomUUID
            newTraceRule(requestId, chain,
                         newCondition(tpDst = Some(500)), 1)
            val pktCtxs = new LinkedList[PacketContext]()
            val wkfl = packetWorkflow(Map(42 -> port1),
                                      packetCtxTrap = pktCtxs)
            wkfl ! PacketWorkflow.HandlePackets(
                List(makePacket(500)).toArray)
            pktCtxs.size() should be (2)
            pktCtxs.pop().runs should be (2)
        }
    }

    feature("Ingress/Egress matching") {
        scenario("Trace generates flow state messages") {
            val requestId1 = UUID.randomUUID
            newTraceRule(requestId1, chain,
                         newCondition(tpDst = Some(500)), 1)
            val frame = makeFrame(500)
            val pktCtx = packetContextFor(frame, port1)
            pktCtx.tracingEnabled(requestId1) should be (false)
            try {
                simulate(pktCtx)
                fail("Should have thrown an exception")
            } catch {
                case TraceRequiredException =>
                    pktCtx.log should be (PacketContext.traceLog)
                    pktCtx.tracingEnabled(requestId1) should be (true)
            }
            val key = TraceKey.fromFlowMatch(
                FlowMatches.fromEthernetPacket(frame))
            pktCtx.traceTx.get(key) should not be null
            pktCtx.traceTx.get(key)
                .containsRequest(requestId1) should be (true)
        }

        scenario("Trace gets enabled on egress host (no tunnel key hint)") {
            val requestId1 = UUID.randomUUID
            newTraceRule(requestId1, chain,
                         newCondition(tpDst = Some(500)), 1)

            val frame = makeFrame(500)
            val key = TraceKey.fromFlowMatch(
                FlowMatches.fromEthernetPacket(frame))
            val context = new TraceContext
            context.enable(UUID.randomUUID)
            context.addRequest(requestId1)
            table.putAndRef(key, context)

            val pktCtx = packetContextFor(frame, port1)
            pktCtx.tracingEnabled(requestId1) should be (false)

            try {
                simulate(pktCtx)
                fail("Should have thrown an exception")
            } catch {
                case TraceRequiredException =>
                    pktCtx.log should be (PacketContext.traceLog)
                    pktCtx.tracingEnabled(requestId1) should be (true)
            }
            pktCtx.traceTx.size should be (0)
            pktCtx.traceFlowId should be (context.flowTraceId)

            // try with similar, but slightly different packet
            // shouldn't match
            val frame2 = makeFrame(500, 10001)
            val pktCtx2 = packetContextFor(frame2, port1)
            pktCtx2.tracingEnabled(requestId1) should be (false)
            try {
                simulate(pktCtx2)
            } catch {
                case TraceRequiredException =>
                    pktCtx2.log should be (PacketContext.traceLog)
                    pktCtx2.tracingEnabled(requestId1) should be (true)
            }
            pktCtx2.traceTx.size should be (1)
            pktCtx2.traceFlowId should not be context.flowTraceId
        }
    }
}
