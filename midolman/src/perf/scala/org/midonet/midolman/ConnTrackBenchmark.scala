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

import java.util.UUID.randomUUID
import java.util.{ArrayList, HashSet, UUID}
import java.util.concurrent.TimeUnit

import scala.collection.JavaConverters._
import scala.concurrent.Future

import org.openjdk.jmh.annotations.{Setup => JmhSetup, _}
import org.openjdk.jmh.infra.Blackhole

import org.midonet.cluster.data.ports.BridgePort
import org.midonet.midolman.rules.{RuleResult, Condition}
import org.midonet.midolman.simulation.Bridge
import org.midonet.midolman.state.{MockStateStorage, FlowStateReplicator}
import org.midonet.midolman.state.ConnTrackState._
import org.midonet.midolman.state.NatState.{NatKey, NatBinding}
import org.midonet.midolman.state.TraceState.{TraceKey, TraceContext}
import org.midonet.midolman.topology.VirtualTopologyActor
import org.midonet.midolman.topology.rcu.ResolvedHost
import org.midonet.midolman.UnderlayResolver.Route
import org.midonet.odp.flows.FlowActionOutput
import org.midonet.packets.{Ethernet, IPv4Addr, MAC}
import org.midonet.packets.util.PacketBuilder._
import org.midonet.sdn.state.{ShardedFlowStateTable, FlowStateTransaction}

object ConnTrackBenchmark {
    val leftMac = MAC.random
    val rightMac = MAC.random

    @State(Scope.Thread)
    class PacketHolder {
        var packet: Ethernet = _

        @JmhSetup(Level.Invocation)
        def setup(): Unit = {
            packet = { { eth addr leftMac -> rightMac } <<
                       { ip4 addr IPv4Addr.random --> IPv4Addr.random } <<
                       { udp ports 5003 ---> 53 } << payload("payload") }
        }
    }
}

@BenchmarkMode(Array(Mode.AverageTime))
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 5)
@Measurement(iterations = 5)
@Fork(5)
@State(Scope.Benchmark)
class ConnTrackBenchmark extends MidolmanBenchmark {
    import org.midonet.midolman.ConnTrackBenchmark._

    registerActors(VirtualTopologyActor -> (() => new VirtualTopologyActor))

    var leftPort: BridgePort = _
    var rightPort: BridgePort = _

    val underlayResolver = new UnderlayResolver {
        override def host= new ResolvedHost(randomUUID(), true, Map(), Map())
        override def peerTunnelInfo(peer: UUID): Option[Route] = None
        override def isVtepTunnellingPort(portNumber: Integer): Boolean = false
        override def isOverlayTunnellingPort(portNumber: Integer): Boolean = false
        override def vtepTunnellingOutputAction: FlowActionOutput = null
    }
    val conntrackTable = new ShardedFlowStateTable[ConnTrackKey, ConnTrackValue].addShard()
    val natTable = new ShardedFlowStateTable[NatKey, NatBinding].addShard()
    val traceTable = new ShardedFlowStateTable[TraceKey, TraceContext].addShard()
    implicit val conntrackTx = new FlowStateTransaction(conntrackTable)
    implicit val natTx = new FlowStateTransaction(natTable)
    implicit val traceTx = new FlowStateTransaction(traceTable)
    var replicator: FlowStateReplicator = _

    @JmhSetup
    def setup(): Unit = {
        newHost("myself", hostId)
        val clusterBridge = newBridge("bridge")
        leftPort = newBridgePort(clusterBridge)
        rightPort = newBridgePort(clusterBridge)
        materializePort(rightPort, hostId, "port0")
        val chain = newInboundChainOnBridge("chain", clusterBridge)
        val fwdCond = new Condition()
        fwdCond.matchForwardFlow = true
        fwdCond.inPortIds = new java.util.HashSet[UUID]()
        fwdCond.inPortIds.add(leftPort.getId)
        newLiteralRuleOnChain(chain, 1, fwdCond, RuleResult.Action.ACCEPT)
        fetchTopology(clusterBridge, chain, leftPort, rightPort)

        val bridge: Bridge = fetchDevice(clusterBridge)
        val macTable = bridge.vlanMacTableMap(0.toShort)
        macTable.add(leftMac, leftPort.getId)
        macTable.add(rightMac, rightPort.getId)
        replicator = new FlowStateReplicator(conntrackTable, natTable,
                                             traceTable,
                                             Future.successful(new MockStateStorage),
                                             underlayResolver,
                                             mockFlowInvalidation,
                                             0)
    }

    @Benchmark
    def benchmarkConntrack(holder: PacketHolder, bh: Blackhole): Unit = {
        bh.consume(sendPacket(leftPort -> holder.packet))
        replicator.accumulateNewKeys(conntrackTx, natTx, traceTx,
                                     leftPort.getId,
                                     List(rightPort.getId).asJava,
                                     new ArrayList(),
                                     new ArrayList())
        conntrackTx.commit()
        conntrackTx.flush()
    }
}
