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

import java.util.UUID

import scala.collection.mutable.Queue

import akka.actor.Props
import akka.testkit.TestActorRef
import com.codahale.metrics.MetricRegistry

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import org.midonet.cluster.DataClient
import org.midonet.cluster.data.{Bridge => ClusterBridge, Chain}
import org.midonet.cluster.data.ports.BridgePort
import org.midonet.cluster.data.rules.{TraceRule => TraceRuleData}
import org.midonet.midolman.PacketWorkflow.AddVirtualWildcardFlow
import org.midonet.midolman.PacketWorkflow.SimulationResult
import org.midonet.midolman.UnderlayResolver.Route
import org.midonet.midolman.config.MidolmanConfig
import org.midonet.midolman.datapath.DatapathChannel
import org.midonet.midolman.monitoring.metrics.PacketPipelineMetrics
import org.midonet.midolman.rules.Condition
import org.midonet.midolman.rules.RuleResult
import org.midonet.midolman.rules.TraceRule
import org.midonet.midolman.simulation.{Coordinator, PacketContext}
import org.midonet.midolman.state.{HappyGoLuckyLeaser, MockStateStorage}
import org.midonet.midolman.state.ConnTrackState.{ConnTrackValue, ConnTrackKey}
import org.midonet.midolman.state.NatState.{NatBinding, NatKey}
import org.midonet.midolman.state.TraceState.{TraceKey, TraceContext}
import org.midonet.midolman.topology._
import org.midonet.midolman.topology.rcu.ResolvedHost
import org.midonet.midolman.util.MidolmanSpec
import org.midonet.odp.{Datapath, DpPort, FlowMatches, Packet}
import org.midonet.odp.flows.FlowActionOutput
import org.midonet.packets.Ethernet
import org.midonet.packets.util.EthBuilder
import org.midonet.packets.util.PacketBuilder._
import org.midonet.sdn.state.ShardedFlowStateTable

@RunWith(classOf[JUnitRunner])
class FlowTracingTest extends MidolmanSpec {

    registerActors(VirtualTopologyActor -> (() => new VirtualTopologyActor),
                   VirtualToPhysicalMapper -> (() => new VirtualToPhysicalMapper))

    var datapath: Datapath = null

    var bridge: ClusterBridge = _
    var port1: BridgePort = _
    var port2: BridgePort = _
    var chain: Chain = _

    override def beforeTest(): Unit = {
        bridge = newBridge("bridge0")
        port1 = newBridgePort(bridge)
        port2 = newBridgePort(bridge)
        materializePort(port1, hostId, "port1")
        materializePort(port2, hostId, "port2")

        chain = newInboundChainOnBridge("my-chain", bridge)
        fetchTopology(bridge, port1, port2, chain)
    }

    private def newTraceRule(requestId: UUID, chain: Chain,
                             condition: Condition, pos: Int) {
        val traceRule = new TraceRuleData(requestId, condition)
            .setChainId(chain.getId).setPosition(pos)
        clusterDataClient.rulesCreate(traceRule)
        fetchDevice(chain)
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
        pktCtx.initialize(NO_CONNTRACK, NO_NAT, HappyGoLuckyLeaser, NO_TRACE)
        val r = force {
            new Coordinator(pktCtx) simulate()
        }
        (r, pktCtx)
    }

    feature("Tracing enabled by rule in chain") {
        scenario("tracing enabled by catchall rule") {
            val requestId = UUID.randomUUID
            newTraceRule(requestId, chain, newCondition(), 1)

            val pktCtx = packetContextFor(makeFrame(1), port1.getId)
            pktCtx.tracingEnabled(requestId) should be (false)
            try {
                simulate(pktCtx)
                fail("Should have thrown an exception")
            } catch {
                case TraceRequiredException => {
                    pktCtx.tracingEnabled(requestId) should be (true)
                    pktCtx.log should be (PacketContext.traceLog)
                }
            }
            val (simRes, _) = simulate(pktCtx)
            simRes should be (AddVirtualWildcardFlow)
        }

        scenario("tracing enabled by specific rule match") {
            val requestId = UUID.randomUUID
            newTraceRule(requestId, chain,
                         newCondition(tpDst = Some(500)), 1)

            val pktCtx = packetContextFor(makeFrame(1), port1.getId)
            pktCtx.tracingEnabled(requestId) should be (false)
            simulate(pktCtx)._1 should be (AddVirtualWildcardFlow)
            pktCtx.tracingEnabled(requestId) should be (false)
            pktCtx.log should not be (PacketContext.traceLog)

            val pktCtx2 = packetContextFor(makeFrame(500), port1.getId)
            pktCtx2.tracingEnabled(requestId) should be (false)
            try {
                simulate(pktCtx2)
                fail("Should have thrown an exception")
            } catch {
                case TraceRequiredException => {
                    pktCtx2.tracingEnabled(requestId) should be (true)
                    pktCtx2.log should be (PacketContext.traceLog)
                }
            }
            simulate(pktCtx2)._1 should be (AddVirtualWildcardFlow)

            val pktCtx3 = packetContextFor(makeFrame(1000), port1.getId)
            pktCtx3.tracingEnabled(requestId) should be (false)
            simulate(pktCtx3)._1 should be (AddVirtualWildcardFlow)
            pktCtx3.tracingEnabled(requestId) should be (false)
            pktCtx3.log should not be (PacketContext.traceLog)
        }

        scenario("Multiple rules matching on different things") {
            val requestId1 = UUID.randomUUID
            val requestId2 = UUID.randomUUID
            newTraceRule(requestId1, chain,
                         newCondition(tpDst = Some(500)), 1)
            newTraceRule(requestId2, chain,
                         newCondition(tpSrc = Some(1000)), 1)

            val pktCtx = packetContextFor(makeFrame(500), port1.getId)
            pktCtx.tracingEnabled(requestId1) should be (false)
            pktCtx.tracingEnabled(requestId2) should be (false)
            try {
                simulate(pktCtx)
                fail("Should have thrown an exception")
            } catch {
                case TraceRequiredException => {
                    pktCtx.log should be (PacketContext.traceLog)
                    pktCtx.tracingEnabled(requestId1) should be (true)
                    pktCtx.tracingEnabled(requestId2) should be (false)
                }
            }
            simulate(pktCtx)._1 should be (AddVirtualWildcardFlow)

            val pktCtx2 = packetContextFor(makeFrame(500, 1000), port1.getId)
            pktCtx2.tracingEnabled(requestId1) should be (false)
            try {
                // should hit second rule
                simulate(pktCtx2)
                fail("Should have thrown an exception")
            } catch {
                case TraceRequiredException => {
                    pktCtx2.tracingEnabled(requestId2) should be (true)
                    pktCtx2.tracingEnabled(requestId1) should be (false)
                    pktCtx2.log should be (PacketContext.traceLog)
                }
            }
            try {
                // should hit first rule
                simulate(pktCtx2)
                fail("Should have thrown an exception")
            } catch {
                case TraceRequiredException => {
                    pktCtx2.tracingEnabled(requestId2) should be (true)
                    pktCtx2.tracingEnabled(requestId1) should be (true)
                    pktCtx2.log should be (PacketContext.traceLog)
                }
            }
            simulate(pktCtx2)._1 should be (AddVirtualWildcardFlow)
        }

        scenario("restart on trace requested exception") {
            val requestId = UUID.randomUUID
            val rule = new TraceRule(requestId, newCondition(tpDst = Some(500)),
                                     UUID.randomUUID, 1)
            val mockWorkflow = new MockPacketWorkflow(requestId, rule)
            val ddaProps = Props { new TestableDDA(mockWorkflow) }
            val ddaRef = TestActorRef(ddaProps)(actorSystem)

            mockWorkflow.startInvokations should be (0)

            ddaRef ! DeduplicationActor.HandlePackets(
                List(makePacket(500)).toArray)
            mockWorkflow.startInvokations should be (2)
            mockWorkflow.reset

            ddaRef ! DeduplicationActor.HandlePackets(
                List(makePacket(1000)).toArray)
            mockWorkflow.startInvokations should be (1)
        }
    }

    class MockPacketWorkflow(val requestId: UUID, val rule :TraceRule)
            extends PacketHandler {
        var startInvokations : Int = 0

        override def start(context: PacketContext)
                : PacketWorkflow.SimulationResult = {
            startInvokations += 1

            val res = new RuleResult(RuleResult.Action.CONTINUE,
                                     UUID.randomUUID)
            rule.process(context, res, UUID.randomUUID, false)

            PacketWorkflow.Drop
        }

        override def drop(context: PacketContext): Unit = {
        }

        def reset(): Unit = {
            startInvokations = 0
        }
    }

    class MockDatapathState extends DatapathState {
        override def getDpPortForInterface(itfName: String)
                : Option[DpPort] = ???
        override def dpPortNumberForTunnelKey(tunnelKey: Long)
                : Option[DpPort] = ???
        override def getVportForDpPortNumber(portNum: Integer)
                : Option[UUID] = { Some(port1.getId) }
        override def getDpPortNumberForVport(vportId: UUID)
                : Option[Integer] = ???
        override def getDpPortName(num: Integer)
                : Option[String] = ???
        override def host = new ResolvedHost(UUID.randomUUID(), true,
                                             Map(), Map())
        override def peerTunnelInfo(peer: UUID)
                : Option[Route] = ???
        override def isVtepTunnellingPort(portNumber: Integer)
                : Boolean = ???
        override def isOverlayTunnellingPort(portNumber: Integer)
                : Boolean = ???
        override def vtepTunnellingOutputAction
                : FlowActionOutput = ???
        override def getDescForInterface(itfName: String) = ???
    }

    class TestableDDA(val workflow2: PacketHandler)
            extends DeduplicationActor(
        injector.getInstance(classOf[MidolmanConfig]),
        new CookieGenerator(1, 1), mockDpChannel, clusterDataClient,
        new ShardedFlowStateTable[ConnTrackKey, ConnTrackValue](),
        new ShardedFlowStateTable[NatKey, NatBinding](),
        new ShardedFlowStateTable[TraceKey, TraceContext](),
        new MockStateStorage(), HappyGoLuckyLeaser,
        new PacketPipelineMetrics(
            injector.getInstance(classOf[MetricRegistry])),
        x => Unit) {
        dpState = new MockDatapathState()
        workflow = workflow2
        context.become(receive)
    }
}
