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

import java.util.{List => JList, LinkedList, UUID}

import scala.collection.JavaConverters._
import scala.concurrent.duration._
import scala.concurrent.Future

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import org.midonet.cluster.data.{Bridge => ClusterBridge, Chain}
import org.midonet.cluster.data.ports.BridgePort
import org.midonet.cluster.data.rules.{TraceRule => TraceRuleData}
import org.midonet.cluster.util.UUIDUtil._
import org.midonet.midolman.PacketWorkflow.AddVirtualWildcardFlow
import org.midonet.midolman.PacketWorkflow.SimulationResult

import org.midonet.midolman.rules.{Condition, NatTarget, RuleResult}
import org.midonet.midolman.PacketWorkflow.{AddVirtualWildcardFlow, SimulationResult}
import org.midonet.midolman.UnderlayResolver.Route
import org.midonet.midolman.simulation.{Bridge, Coordinator, PacketContext}
import org.midonet.midolman.state.{FlowStatePackets, HappyGoLuckyLeaser, TraceState}
import org.midonet.midolman.state.TraceState.{TraceKey, TraceContext}
import org.midonet.midolman.topology._
import org.midonet.midolman.util.MidolmanSpec
import org.midonet.odp.flows.{FlowAction, FlowActions, FlowKeys}
import org.midonet.odp.{FlowMatches, Packet}
import org.midonet.packets.{Ethernet, IPv4Addr}
import org.midonet.packets.util.EthBuilder
import org.midonet.packets.util.PacketBuilder._
import org.midonet.sdn.state.ShardedFlowStateTable
import org.midonet.sdn.state.FlowStateTransaction

@RunWith(classOf[JUnitRunner])
class FlowTracingTest extends MidolmanSpec {
    registerActors(VirtualTopologyActor -> (() => new VirtualTopologyActor))

    var bridge: ClusterBridge = _
    var port1: BridgePort = _
    var port2: BridgePort = _
    var port3: BridgePort = _

    var chain: Chain = _

    var hostId2: UUID = _
    val host1addr = IPv4Addr("10.0.0.1").toInt
    val host2addr = IPv4Addr("10.0.0.2").toInt

    val dstAddr = IPv4Addr("192.168.0.2")

    val table = new ShardedFlowStateTable[TraceKey,TraceContext](clock)
        .addShard()
    implicit val traceTx = new FlowStateTransaction(table)

    override def beforeTest(): Unit = {
        val host2 = newHost("host2")
        hostId2 = host2.getId

        bridge = newBridge("bridge0")
        port1 = newBridgePort(bridge)
        port2 = newBridgePort(bridge)
        port3 = newBridgePort(bridge)
        materializePort(port1, hostId, "port1")
        materializePort(port2, hostId, "port2")
        materializePort(port3, hostId2, "port3")

        chain = newInboundChainOnBridge("my-chain", bridge)

        fetchTopology(bridge, port1, port2, port3, chain)
    }

    private def newTraceRule(requestId: UUID, chain: Chain,
                             condition: Condition, pos: Int) {
        val traceRule = new TraceRuleData(requestId, condition, Long.MaxValue)
            .setChainId(chain.getId).setPosition(pos)
        clusterDataClient.rulesCreate(traceRule)
        fetchDevice(chain)
    }

    private def makeFrame(tpDst: Short, tpSrc: Short = 10101): Ethernet =
        { eth addr "00:02:03:04:05:06" -> "00:20:30:40:50:60" } <<
        { ip4 addr "192.168.0.1" --> dstAddr } <<
        { udp ports tpSrc ---> tpDst } << payload("foobar")

    implicit def eth2Packet(frame: Ethernet): Packet = {
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

            val pktCtx = packetContextFor(makeFrame(1), port1.getId)
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

            val pktCtx = packetContextFor(makeFrame(1), port1.getId)
            pktCtx.tracingEnabled(requestId) should be (false)
            simulate(pktCtx)._1 should be (AddVirtualWildcardFlow)
            pktCtx.tracingEnabled(requestId) should be (false)
            pktCtx.log should not be PacketContext.traceLog

            val pktCtx2 = packetContextFor(makeFrame(500), port1.getId)
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

            val pktCtx3 = packetContextFor(makeFrame(1000), port1.getId)
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

            val pktCtx = packetContextFor(makeFrame(500), port1.getId)
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

            val pktCtx2 = packetContextFor(makeFrame(500, 1000), port1.getId)
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
            val wkfl = packetWorkflow(Map(42 -> port1.getId),
                                      packetCtxTrap = pktCtxs)
            wkfl ! PacketWorkflow.HandlePackets(
                List(makePacket(500)).toArray)
            pktCtxs.size() should be (2)
            pktCtxs.pop().runs should be (2)
        }
    }

    feature("Tracing across a simulation restart") {
        scenario("The trace request should not persist across restarts") {
            var trace = true
            var restart = true
            val requestId = UUID.randomUUID
            val pktCtxs = new LinkedList[PacketContext]()
            val wkfl = packetWorkflow(Map(42 -> port1.getId), workflowTrap = pktCtx => {
                if (trace) {
                    trace = false
                    pktCtx.enableTracing(requestId)
                    throw TraceRequiredException
                }
                if (restart) {
                    restart = false
                    throw new NotYetException(Future.successful(null))
                }
                AddVirtualWildcardFlow
            }, packetCtxTrap = pktCtxs)
            wkfl ! PacketWorkflow.HandlePackets(Array(makePacket(500)))
            pktCtxs.size() should be (3)
            val pktCtx = pktCtxs.pop()
            pktCtx.runs should be (3)
            pktCtx should be (pktCtxs.pop())
            pktCtx should be (pktCtxs.pop())
        }

        scenario("The trace request should not persist across different packets") {
            var trace = true
            val requestId = UUID.randomUUID
            val pktCtxs = new LinkedList[PacketContext]()
            val wkfl = packetWorkflow(Map(42 -> port1.getId), workflowTrap = pktCtx => {
                if (trace) {
                    trace = false
                    pktCtx.enableTracing(requestId)
                    throw TraceRequiredException
                }
                AddVirtualWildcardFlow
            }, packetCtxTrap = pktCtxs)
            wkfl ! PacketWorkflow.HandlePackets(Array(makePacket(500), makePacket(501)))
            pktCtxs.size() should be (3)
            val tracedPktCtx = pktCtxs.pop()
            tracedPktCtx.tracingEnabled.shouldBe(true)
            tracedPktCtx should be (pktCtxs.pop())
            val nonTracePktCtx = pktCtxs.pop()
            nonTracePktCtx.tracingEnabled.shouldBe(false)
        }

        scenario("The trace request should not persist across different packets when an exception is thrown") {
            var trace = true
            val requestId = UUID.randomUUID
            val pktCtxs = new LinkedList[PacketContext]()
            val wkfl = packetWorkflow(Map(42 -> port1.getId), workflowTrap = pktCtx => {
                if (trace) {
                    trace = false
                    pktCtx.enableTracing(requestId)
                    throw TraceRequiredException
                }
                throw new Exception()
            }, packetCtxTrap = pktCtxs)
            wkfl ! PacketWorkflow.HandlePackets(Array(makePacket(500), makePacket(501)))
            pktCtxs.size() should be (3)
            val tracedPktCtx = pktCtxs.get(0)
            tracedPktCtx should be (pktCtxs.get(1))
            tracedPktCtx.tracingEnabled.shouldBe(false) // cleared by the exception handler
            val nonTracePktCtx = pktCtxs.get(2)
            tracedPktCtx.tracingEnabled.shouldBe(false)
        }
    }

    feature("Ingress/Egress matching") {
        scenario("Trace gets enabled on egress host (no tunnel key hint)") {
            val requestId1 = UUID.randomUUID
            newTraceRule(requestId1, chain,
                         newCondition(tpDst = Some(500)), 1)

            val frame = makeFrame(500)
            val key = TraceKey.fromFlowMatch(
                FlowMatches.fromEthernetPacket(frame))

            val pktCtx = packetContextFor(frame, port1.getId)
            pktCtx.tracingEnabled(requestId1) shouldBe false

            try {
                simulate(pktCtx)
                fail("Should have thrown an exception")
            } catch {
                case TraceRequiredException =>
                    pktCtx.log should be (PacketContext.traceLog)
                    pktCtx.tracingEnabled(requestId1) should be (true)
            }

            // try with similar, but slightly different packet
            // shouldn't match
            val frame2 = makeFrame(500, 10001)
            val pktCtx2 = packetContextFor(frame2, port1.getId)
            pktCtx2.tracingEnabled(requestId1) should be (false)
            try {
                simulate(pktCtx2)
            } catch {
                case TraceRequiredException =>
                    pktCtx2.log should be (PacketContext.traceLog)
                    pktCtx2.tracingEnabled(requestId1) should be (true)
            }
            pktCtx2.traceFlowId should not be pktCtx.traceFlowId
        }

        scenario("Trace requests should be different for 2 identical packets, " +
                     " state should only forward to egress and shouldn't hang around long") {
            val requestId = UUID.randomUUID
            val pktCtxs = new LinkedList[PacketContext]()
            val packetsSent = new LinkedList[Ethernet]()

            newTraceRule(requestId, chain,
                         newCondition(tpDst = Some(500)), 1)

            val frame = makeFrame(500)
            val output = FlowActions.output(23)
            val wkfl = packetWorkflow(
                Map(42 -> port1.getId),
                peers = Map(hostId -> Route(host2addr, host1addr, output),
                            hostId2 -> Route(host1addr, host2addr, output)),
                packetCtxTrap = pktCtxs)
            mockDpChannel.packetsExecuteSubscribe(
                (p: Packet, actions: JList[FlowAction]) => {
                    packetsSent.add(p.getEthernet.clone)
                })

            wkfl ! PacketWorkflow.HandlePackets(Array(frame, frame))

            pktCtxs.size shouldBe 4 // twice for each packet
            val flowTrace1 = pktCtxs.get(0).traceContext.flowTraceId
            val flowTrace2 = pktCtxs.get(2).traceContext.flowTraceId
            flowTrace1.equals(flowTrace2) shouldBe false
            packetsSent.size shouldBe 4

            val packet0 = FlowStatePackets.parseDatagram(packetsSent.get(0))
            FlowStatePackets.uuidFromProto(
                packet0.getNewState(0).getTraceEntry(0).getFlowTraceId()) shouldBe flowTrace1
            val packet1 = packetsSent.get(1)
            packet1.equals(frame) shouldBe true

            val packet2 = FlowStatePackets.parseDatagram(packetsSent.get(2))
            FlowStatePackets.uuidFromProto(
                packet2.getNewState(0).getTraceEntry(0).getFlowTraceId()) shouldBe flowTrace2
            val packet3 = packetsSent.get(3)
            packet3.equals(frame) shouldBe true

            // shouldn't have put anything in the trace table
            val traceKey = pktCtxs.get(1).traceKeyForEgress
            traceKey shouldBe pktCtxs.get(2).traceKeyForEgress

            wkfl.underlyingActor.traceStateTable.get(traceKey) shouldBe null
            wkfl.underlyingActor.traceStateTable.get(traceKey) shouldBe null

            val tunnelPort = 10101
            val wkflEgress = packetWorkflow(Map(42 -> port3.getId),
                                            tunnelPorts = List(tunnelPort),
                                            packetCtxTrap = pktCtxs)
            val egressTable = wkflEgress.underlyingActor.traceStateTable

            def tunnelPacket(eth: Ethernet, tunnelKey: Long): Packet = {
                val fmatch = FlowMatches.fromEthernetPacket(eth)
                fmatch.addKey(FlowKeys.tunnel(tunnelKey, 0, 0, 0))
                fmatch.addKey(FlowKeys.inPort(tunnelPort))
                    .setInputPortNumber(tunnelPort)
                new Packet(eth, fmatch)
            }

            val statePacket1 = tunnelPacket(packetsSent.get(0),
                                           FlowStatePackets.TUNNEL_KEY)
            wkflEgress ! PacketWorkflow.HandlePackets(Array(statePacket1))
            egressTable.get(traceKey) should not be (null)

            pktCtxs.clear()
            wkflEgress ! PacketWorkflow.HandlePackets(
                Array(tunnelPacket(frame,
                                   tunnelPort | TraceState.TraceTunnelKeyMask)))
            pktCtxs.get(0).traceContext.flowTraceId shouldBe flowTrace1

            val statePacket2 = tunnelPacket(packetsSent.get(2),
                                           FlowStatePackets.TUNNEL_KEY)
            wkflEgress ! PacketWorkflow.HandlePackets(Array(statePacket2))

            pktCtxs.clear()
            wkflEgress ! PacketWorkflow.HandlePackets(
                Array(tunnelPacket(frame,
                                   tunnelPort | TraceState.TraceTunnelKeyMask)))
            pktCtxs.get(0).traceContext.flowTraceId shouldBe flowTrace2
            egressTable.get(traceKey) should not be (null)

            clock.time += (5 seconds).toNanos
            wkflEgress ! CheckBackchannels
            egressTable.get(traceKey) shouldBe (null)
        }
    }
}
