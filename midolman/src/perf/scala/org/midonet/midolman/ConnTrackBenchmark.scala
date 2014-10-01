/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */

package org.midonet.midolman

import java.util.{ArrayList, UUID}
import java.util.concurrent.TimeUnit

import scala.collection.mutable

import org.openjdk.jmh.annotations.{Setup => JmhSetup, Level, Benchmark, Scope, State, Fork, Measurement, Warmup, OutputTimeUnit, Mode, BenchmarkMode}
import org.openjdk.jmh.infra.Blackhole

import org.midonet.cluster.data.ports.BridgePort
import org.midonet.midolman.UnderlayResolver.Route
import org.midonet.midolman.rules.{RuleResult, Condition}
import org.midonet.midolman.simulation.Bridge
import org.midonet.midolman.state.{MockStateStorage, FlowStateReplicator}
import org.midonet.midolman.state.ConnTrackState._
import org.midonet.midolman.state.NatState.{NatKey, NatBinding}
import org.midonet.midolman.topology.VirtualTopologyActor
import org.midonet.midolman.topology.rcu.Host
import org.midonet.odp.Datapath
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
        override def host: Host = new Host(UUID.randomUUID(), true, 0, "midonet",
                                           Map(), Map())
        override def peerTunnelInfo(peer: UUID): Option[Route] = None
        override def isVtepTunnellingPort(portNumber: Short): Boolean = false
        override def isOverlayTunnellingPort(portNumber: Short): Boolean = false
        override def vtepTunnellingOutputAction: FlowActionOutput = null
    }
    val conntrackTable = new ShardedFlowStateTable[ConnTrackKey, ConnTrackValue].addShard()
    val natTable = new ShardedFlowStateTable[NatKey, NatBinding].addShard()
    implicit val conntrackTx = new FlowStateTransaction(conntrackTable)
    implicit val natTx = new FlowStateTransaction(natTable)
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
                                             new MockStateStorage,
                                             underlayResolver, _ => { },
                                             new Datapath(1, "midonet", null))
    }

    @Benchmark
    def benchmarkConntrack(holder: PacketHolder, bh: Blackhole): Unit = {
        bh.consume(sendPacket(leftPort -> holder.packet))
        replicator.accumulateNewKeys(conntrackTx, natTx, leftPort.getId,
                                     rightPort.getId, null, mutable.Set(),
                                     new ArrayList())
        conntrackTx.commit()
        conntrackTx.flush()
    }
}
