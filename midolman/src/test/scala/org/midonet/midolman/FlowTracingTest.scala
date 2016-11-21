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

import java.util.{LinkedList, List => JList, UUID}

import scala.concurrent.duration._
import scala.concurrent.{Future, Promise}

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import org.midonet.cluster.flowstate.proto.FlowState
import org.midonet.midolman.PacketWorkflow.{AddVirtualWildcardFlow, SimulationResult}
import org.midonet.midolman.UnderlayResolver.Route
import org.midonet.midolman.simulation.{Bridge, PacketContext, Simulator}
import org.midonet.midolman.state.TraceState.{TraceContext, TraceKey}
import org.midonet.midolman.state.{FlowStateAgentPackets => FlowStatePackets, HappyGoLuckyLeaser, TraceState}
import org.midonet.midolman.util.MidolmanSpec
import org.midonet.odp.flows.{FlowAction, FlowActions, FlowKeys}
import org.midonet.odp.{FlowMatches, Packet}
import org.midonet.packets.util.PacketBuilder._
import org.midonet.packets.{Ethernet, IPv4Addr, SbeEncoder}
import org.midonet.packets.TunnelKeys.TraceBit
import org.midonet.sdn.state.{FlowStateTransaction, ShardedFlowStateTable}

@RunWith(classOf[JUnitRunner])
class FlowTracingTest extends MidolmanSpec {

    var bridge: UUID = _
    var port1: UUID = _
    var port2: UUID = _
    var port3: UUID = _
    var chain: UUID = _

    var hostId2: UUID = _
    val host1addr = IPv4Addr("10.0.0.1").toInt
    val host2addr = IPv4Addr("10.0.0.2").toInt

    val dstAddr = IPv4Addr("192.168.0.2")
    val table = new ShardedFlowStateTable[TraceKey,TraceContext](clock)
        .addShard()
    implicit val traceTx = new FlowStateTransaction(table)

    override def beforeTest(): Unit = {
        hostId2 = newHost("host2")
        bridge = newBridge("bridge0")
        port1 = newBridgePort(bridge)
        port2 = newBridgePort(bridge)
        port3 = newBridgePort(bridge)
        materializePort(port1, hostId, "port1")
        materializePort(port2, hostId, "port2")
        materializePort(port3, hostId2, "port3")

        chain = newInboundChainOnBridge("my-chain", bridge)
        fetchChains(chain)
        fetchPorts(port1, port2, port3)
        fetchDevice[Bridge](bridge)
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
            Simulator.simulate(pktCtx)
        }
        (r, pktCtx)
    }

    feature("Tracing enabled by rule in chain") {
        scenario("tracing enabled by catchall rule") {
            val requestId = UUID.randomUUID
            newTraceRuleOnChain(chain, 1, newCondition(), requestId)

            val pktCtx = packetContextFor(makeFrame(1), port1)
            pktCtx.tracingEnabled(requestId) shouldBe false
            try {
                simulate(pktCtx)
                fail("Should have thrown an exception")
            } catch {
                case TraceRequiredException =>
                    pktCtx.tracingEnabled(requestId) shouldBe true
                    pktCtx.log should be (PacketContext.traceLog)
            }
            val (simRes, _) = simulate(pktCtx)
            simRes should be (AddVirtualWildcardFlow)
        }

        scenario("tracing enabled by specific rule match") {
            val requestId = UUID.randomUUID
            newTraceRuleOnChain(chain, 1,
                                newCondition(tpDst = Some(500)), requestId)

            val pktCtx = packetContextFor(makeFrame(1), port1)
            pktCtx.tracingEnabled(requestId) shouldBe false
            simulate(pktCtx)._1 should be (AddVirtualWildcardFlow)
            pktCtx.tracingEnabled(requestId) shouldBe false
            pktCtx.log should not be PacketContext.traceLog

            val pktCtx2 = packetContextFor(makeFrame(500), port1)
            pktCtx2.tracingEnabled(requestId) shouldBe false
            try {
                simulate(pktCtx2)
                fail("Should have thrown an exception")
            } catch {
                case TraceRequiredException =>
                    pktCtx2.tracingEnabled(requestId) shouldBe true
                    pktCtx2.log should be (PacketContext.traceLog)
            }
            simulate(pktCtx2)._1 should be (AddVirtualWildcardFlow)

            val pktCtx3 = packetContextFor(makeFrame(1000), port1)
            pktCtx3.tracingEnabled(requestId) shouldBe false
            simulate(pktCtx3)._1 should be (AddVirtualWildcardFlow)
            pktCtx3.tracingEnabled(requestId) shouldBe false
            pktCtx3.log should not be PacketContext.traceLog
        }

        scenario("Multiple rules matching on different things") {
            val requestId1 = UUID.randomUUID
            val requestId2 = UUID.randomUUID
            newTraceRuleOnChain(chain, 1,
                                newCondition(tpDst = Some(500)), requestId1)
            newTraceRuleOnChain(chain, 1,
                                newCondition(tpSrc = Some(1000)), requestId2)

            val pktCtx = packetContextFor(makeFrame(500), port1)
            pktCtx.tracingEnabled(requestId1) shouldBe false
            pktCtx.tracingEnabled(requestId2) shouldBe false
            try {
                simulate(pktCtx)
                fail("Should have thrown an exception")
            } catch {
                case TraceRequiredException =>
                    pktCtx.log should be (PacketContext.traceLog)
                    pktCtx.tracingEnabled(requestId1) shouldBe true
                    pktCtx.tracingEnabled(requestId2) shouldBe false
            }
            simulate(pktCtx)._1 should be (AddVirtualWildcardFlow)

            val pktCtx2 = packetContextFor(makeFrame(500, 1000), port1)
            pktCtx2.tracingEnabled(requestId1) shouldBe false
            try {
                // should hit second rule
                simulate(pktCtx2)
                fail("Should have thrown an exception")
            } catch {
                case TraceRequiredException =>
                    pktCtx2.tracingEnabled(requestId2) shouldBe true
                    pktCtx2.tracingEnabled(requestId1) shouldBe false
                    pktCtx2.log should be (PacketContext.traceLog)
            }
            try {
                // should hit first rule
                simulate(pktCtx2)
                fail("Should have thrown an exception")
            } catch {
                case TraceRequiredException =>
                    pktCtx2.tracingEnabled(requestId2) shouldBe true
                    pktCtx2.tracingEnabled(requestId1) shouldBe true
                    pktCtx2.log should be (PacketContext.traceLog)
            }
            simulate(pktCtx2)._1 should be (AddVirtualWildcardFlow)
        }

        scenario("restart on trace requested exception") {
            val requestId = UUID.randomUUID
            newTraceRuleOnChain(chain, 1,
                                newCondition(tpDst = Some(500)), requestId)
            val pktCtxs = new LinkedList[PacketContext]()
            val wkfl = packetWorkflow(Map(42 -> port1),
                                      packetCtxTrap = pktCtxs)
            wkfl.handlePackets(makePacket(500))
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
            val wkfl = packetWorkflow(Map(42 -> port1), workflowTrap = pktCtx => {
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
            wkfl.handlePackets(makePacket(500))
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
            val wkfl = packetWorkflow(Map(42 -> port1), workflowTrap = pktCtx => {
                if (trace) {
                    trace = false
                    pktCtx.enableTracing(requestId)
                    throw TraceRequiredException
                }
                AddVirtualWildcardFlow
            }, packetCtxTrap = pktCtxs)
            wkfl.handlePackets(makePacket(500), makePacket(501))
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
            val wkfl = packetWorkflow(Map(42 -> port1), workflowTrap = pktCtx => {
                if (trace) {
                    trace = false
                    pktCtx.enableTracing(requestId)
                    throw TraceRequiredException
                }
                throw new Exception()
            }, packetCtxTrap = pktCtxs)
            wkfl.handlePackets(makePacket(500), makePacket(501))
            pktCtxs.size() should be (3)
            val tracedPktCtx = pktCtxs.get(0)
            tracedPktCtx should be (pktCtxs.get(1))
            tracedPktCtx.tracingEnabled.shouldBe(false) // cleared by the exception handler
            val nonTracePktCtx = pktCtxs.get(2)
            tracedPktCtx.tracingEnabled.shouldBe(false)
        }

        scenario("Flow trace id persists across NotYetException") {
            var traceTriggered = false
            var notYetTriggered = false
            val requestId = UUID.randomUUID
            val flowTraceIds = new LinkedList[UUID]()

            /** There should be 4 calls to workflow.
              * 1. throws TraceRequiredException
              * 2. throws NotYetException
              * 3. throws TraceRequiredException
              *    (not yet clears the trace enabled status)
              * 4. succussfully goes all the way through
              */
            val wkfl = packetWorkflow(Map(42 -> port1),
                                      workflowTrap = pktCtx => {
                                          try {
                                              if (!traceTriggered) {
                                                  traceTriggered = true
                                                  pktCtx.enableTracing(requestId)
                                                  throw TraceRequiredException
                                              }
                                              if (!notYetTriggered) {
                                                  notYetTriggered = true
                                                  traceTriggered = false
                                                  throw new NotYetException(
                                                      Promise.successful[Any](null).future)
                                              }
                                          } finally {
                                              flowTraceIds.add(pktCtx.traceFlowId)
                                          }
                                          AddVirtualWildcardFlow
            })
            wkfl.handlePackets(makePacket(500))
            flowTraceIds.size shouldBe 4
            flowTraceIds.get(0) shouldBe flowTraceIds.get(1)
            flowTraceIds.get(1) shouldBe flowTraceIds.get(2)
            flowTraceIds.get(2) shouldBe flowTraceIds.get(3)
        }
    }

    feature("Ingress/Egress matching") {
        scenario("Trace gets enabled on egress host (no tunnel key hint)") {
            val requestId1 = UUID.randomUUID
            newTraceRuleOnChain(chain, 1,
                                newCondition(tpDst = Some(500)),
                                requestId1)

            val frame = makeFrame(500)
            val key = TraceKey.fromFlowMatch(
                FlowMatches.fromEthernetPacket(frame))
            val pktCtx = packetContextFor(frame, port1)
            pktCtx.tracingEnabled(requestId1) shouldBe false

            try {
                simulate(pktCtx)
                fail("Should have thrown an exception")
            } catch {
                case TraceRequiredException =>
                    pktCtx.log should be (PacketContext.traceLog)
                    pktCtx.tracingEnabled(requestId1) shouldBe true
            }

            // try with similar, but slightly different packet
            // shouldn't match
            val frame2 = makeFrame(500, 10001)
            val pktCtx2 = packetContextFor(frame2, port1)
            pktCtx2.tracingEnabled(requestId1) shouldBe false
            try {
                simulate(pktCtx2)
            } catch {
                case TraceRequiredException =>
                    pktCtx2.log should be (PacketContext.traceLog)
                    pktCtx2.tracingEnabled(requestId1) shouldBe true
            }
            pktCtx2.traceFlowId should not be pktCtx.traceFlowId
        }

        scenario("Trace requests should be different for 2 identical packets, " +
                     " state should only forward to egress and shouldn't hang around long") {
            val requestId = UUID.randomUUID
            val pktCtxs = new LinkedList[PacketContext]()
            val packetsSent = new LinkedList[Ethernet]()
            val statePacketsSent = new LinkedList[Ethernet]()

            newTraceRuleOnChain(chain, 1,
                                newCondition(tpDst = Some(500)), requestId)
            val frame = makeFrame(500)
            val output = FlowActions.output(23)
            val wkfl = packetWorkflow(
                Map(42 -> port1),
                peers = Map(hostId -> Route(host2addr, host1addr, output),
                            hostId2 -> Route(host1addr, host2addr, output)),
                packetCtxTrap = pktCtxs)
            mockDpChannel.packetsExecuteSubscribe(
                (p: Packet, actions: JList[FlowAction]) => {
                    packetsSent.add(p.getEthernet.clone)
                })
            mockDpChannel.statePacketsExecuteSubscribe(
                (p: Packet, actions: JList[FlowAction]) => {
                    statePacketsSent.add(p.getEthernet.clone)
                })

            wkfl.handlePackets(frame, frame)

            pktCtxs.size shouldBe 4 // twice for each packet
            val flowTrace1 = pktCtxs.get(0).traceContext.flowTraceId
            val flowTrace2 = pktCtxs.get(2).traceContext.flowTraceId
            flowTrace1.equals(flowTrace2) shouldBe false
            packetsSent.size shouldBe 2
            statePacketsSent.size shouldBe 2

            val encoder = new SbeEncoder
            def parse(p: Ethernet): FlowState = {
                encoder.decodeFrom(FlowStatePackets.parseDatagram(p).getData)
            }

            val packet0 = parse(statePacketsSent.get(0))
            packet0.conntrack.iterator().hasNext() shouldBe false
            packet0.nat.iterator().hasNext() shouldBe false
            val iter0 = packet0.trace.iterator()
            iter0.hasNext() shouldBe true
            FlowStatePackets.uuidFromSbe(
                iter0.next().flowTraceId) shouldBe flowTrace1

            val packet1 = packetsSent.get(0)
            packet1.equals(frame) shouldBe true

            val packet2 = parse(statePacketsSent.get(1))
            packet2.conntrack.iterator().hasNext() shouldBe false
            packet2.nat.iterator().hasNext() shouldBe false
            val iter2 = packet2.trace.iterator()
            iter2.hasNext() shouldBe true
            FlowStatePackets.uuidFromSbe(
                iter2.next().flowTraceId) shouldBe flowTrace2
            val packet3 = packetsSent.get(1)
            packet3.equals(frame) shouldBe true

            // shouldn't have put anything in the trace table
            val traceKey = pktCtxs.get(1).traceKeyForEgress
            traceKey shouldBe pktCtxs.get(2).traceKeyForEgress

            wkfl.traceStateTable.get(traceKey) shouldBe null
            wkfl.traceStateTable.get(traceKey) shouldBe null

            val tunnelPort = 10101
            val wkflEgress = packetWorkflow(Map(42 -> port3),
                                            tunnelPorts = List(tunnelPort),
                                            packetCtxTrap = pktCtxs)
            val egressTable = wkflEgress.traceStateTable

            def tunnelPacket(eth: Ethernet, tunnelKey: Long): Packet = {
                val fmatch = FlowMatches.fromEthernetPacket(eth)
                fmatch.addKey(FlowKeys.tunnel(tunnelKey, 0, 0, 0))
                fmatch.addKey(FlowKeys.inPort(tunnelPort))
                    .setInputPortNumber(tunnelPort)
                new Packet(eth, fmatch)
            }

            val statePacket1 = tunnelPacket(statePacketsSent.get(0),
                                           FlowStatePackets.TUNNEL_KEY)
            wkflEgress.handlePackets(statePacket1)
            egressTable.get(traceKey) should not be (null)

            pktCtxs.clear()
            wkflEgress.handlePackets(
                tunnelPacket(frame, TraceBit.set(tunnelPort.toInt)))
            pktCtxs.get(0).traceContext.flowTraceId shouldBe flowTrace1

            val statePacket2 = tunnelPacket(statePacketsSent.get(1),
                                           FlowStatePackets.TUNNEL_KEY)
            wkflEgress.handlePackets(statePacket2)

            pktCtxs.clear()
            wkflEgress.handlePackets(
                tunnelPacket(frame, TraceBit.set(tunnelPort.toInt)))
            pktCtxs.get(0).traceContext.flowTraceId shouldBe flowTrace2
            egressTable.get(traceKey) should not be (null)

            clock.time += (5 seconds).toNanos
            wkflEgress.process() // check the backchannels
            egressTable.get(traceKey) shouldBe (null)
        }
    }
}
