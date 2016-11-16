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

import java.util.UUID
import java.util.concurrent.TimeUnit

import scala.concurrent.Future

import org.openjdk.jmh.annotations.{Setup => JmhSetup, _}
import org.openjdk.jmh.infra.Blackhole

import org.midonet.midolman.PacketWorkflow.SimulationResult
import org.midonet.midolman.UnderlayResolver.Route
import org.midonet.midolman.config.MidolmanConfig
import org.midonet.midolman.rules.{Condition, RuleResult}
import org.midonet.midolman.simulation.{Bridge, PacketContext}
import org.midonet.midolman.state.ConnTrackState._
import org.midonet.midolman.state.NatState.NatKey
import org.midonet.midolman.state.TraceState.{TraceContext, TraceKey}
import org.midonet.midolman.state.{FlowStateReplicator, MockStateStorage}
import org.midonet.odp.flows.FlowActionOutput
import org.midonet.packets.NatState.NatBinding
import org.midonet.packets.util.PacketBuilder._
import org.midonet.packets.{IPv4Addr, MAC}
import org.midonet.sdn.state.{FlowStateTransaction, ShardedFlowStateTable}

@BenchmarkMode(Array(Mode.AverageTime))
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 5)
@Measurement(iterations = 5)
@Fork(5)
@State(Scope.Benchmark)
class ConnTrackBenchmark extends MidolmanBenchmark {

    val leftMac = MAC.random
    val rightMac = MAC.random

    var leftPortId: UUID = _
    var rightPortId: UUID = _

    val underlayResolver = new UnderlayResolver {
        override def peerTunnelInfo(peer: UUID): Option[Route] = None
        override def isVtepTunnellingPort(portNumber: Int): Boolean = false
        override def isOverlayTunnellingPort(portNumber: Int): Boolean = false
        override def isVppTunnellingPort(portNumber: Int): Boolean = false
        override def vtepTunnellingOutputAction: FlowActionOutput = null
        override def tunnelRecircOutputAction: FlowActionOutput = null
        override def hostRecircOutputAction: FlowActionOutput = null
    }
    val conntrackTable = new ShardedFlowStateTable[ConnTrackKey, ConnTrackValue].addShard()
    val natTable = new ShardedFlowStateTable[NatKey, NatBinding].addShard()
    val traceTable = new ShardedFlowStateTable[TraceKey, TraceContext].addShard()
    implicit val conntrackTx = new FlowStateTransaction(conntrackTable)
    implicit val natTx = new FlowStateTransaction(natTable)
    implicit val traceTx = new FlowStateTransaction(traceTable)

    val packet = { { eth addr leftMac -> rightMac } <<
                   { ip4 addr IPv4Addr.random --> IPv4Addr.random } <<
                   { udp ports 5003 ---> 53 } << payload("payload") }

    var replicator: FlowStateReplicator = _
    var packetContext: PacketContext = _

    @JmhSetup
    def setup(): Unit = {
        newHost("myself", hostId)
        val clusterBridgeId: UUID = newBridge("bridge")
        leftPortId = newBridgePort(clusterBridgeId)
        rightPortId = newBridgePort(clusterBridgeId)
        materializePort(rightPortId, hostId, "port0")
        val chainId = newInboundChainOnBridge("chain", clusterBridgeId)
        val fwdCond = new Condition()
        fwdCond.matchForwardFlow = true
        fwdCond.inPortIds = new java.util.HashSet[UUID]()
        fwdCond.inPortIds.add(leftPortId)
        newLiteralRuleOnChain(chainId, 1, fwdCond, RuleResult.Action.ACCEPT)
        fetchChains(chainId)
        fetchPorts(leftPortId, rightPortId)

        val bridge = fetchDevice[Bridge](clusterBridgeId)
        val macTable = bridge.vlanMacTableMap(0.toShort)
        macTable.add(leftMac, leftPortId)
        macTable.add(rightMac, rightPortId)
        replicator = new FlowStateReplicator(conntrackTable, natTable,
                                             traceTable,
                                             hostId,
                                             peerResolver,
                                             underlayResolver,
                                             mockFlowInvalidation,
                                             MidolmanConfig.forTests)
        packetContext = packetContextFor(packet, leftPortId)
    }

    @Benchmark
    def benchmarkConntrack(bh: Blackhole): (SimulationResult, PacketContext) = {
        val res = simulate(packetContext)
        replicator.accumulateNewKeys(packetContext)
        conntrackTx.flush()
        res
    }
}
