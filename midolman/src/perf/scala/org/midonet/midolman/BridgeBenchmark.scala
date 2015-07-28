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
import java.util.concurrent.TimeUnit

import org.openjdk.jmh.annotations.{Setup => JmhSetup, _}
import org.openjdk.jmh.infra.Blackhole

import org.midonet.midolman.PacketWorkflow.SimulationResult
import org.midonet.midolman.simulation.{PacketContext, Bridge}
import org.midonet.midolman.topology.VirtualTopologyActor
import org.midonet.packets.MAC
import org.midonet.packets.util.PacketBuilder._

@BenchmarkMode(Array(Mode.AverageTime))
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 5)
@Measurement(iterations = 5)
@Fork(5)
@State(Scope.Benchmark)
class BridgeBenchmark extends MidolmanBenchmark {
    registerActors(VirtualTopologyActor -> (() => new VirtualTopologyActor))

    val leftMac = MAC.random
    val rightMac = MAC.random
    val packet = { eth addr leftMac -> rightMac }

    var leftPortId: UUID = _
    var rightPortId: UUID = _
    var packetContext: PacketContext = _

    @JmhSetup
    def setup(): Unit = {
        newHost("myself", hostId)
        val clusterBridgeId: UUID = newBridge("bridge")
        leftPortId = newBridgePort(clusterBridgeId)
        rightPortId = newBridgePort(clusterBridgeId)
        materializePort(rightPortId, hostId, "port0")
        fetchPorts(leftPortId, rightPortId)

        val bridge = fetchDevice[Bridge](clusterBridgeId)
        val macTable = bridge.vlanMacTableMap(0.toShort)
        macTable.add(leftMac, leftPortId)
        macTable.add(rightMac, rightPortId)

        packetContext = packetContextFor(packet, leftPortId)
    }

    @Benchmark
    def benchmarkConntrack(bh: Blackhole): (SimulationResult, PacketContext) =
        simulate(packetContext)
}
