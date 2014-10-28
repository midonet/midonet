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

package org.midonet.odp

import java.nio.ByteBuffer
import java.util.concurrent.{ThreadLocalRandom, TimeUnit}
import java.util.{Arrays, List => JList}

import scala.concurrent.duration.Duration
import scala.concurrent.{Await, ExecutionContext}

import org.openjdk.jmh.annotations._

import org.midonet.odp.flows.{FlowAction, FlowActions, FlowKey}
import org.midonet.odp.ports.{GreTunnelPort, VxLanTunnelPort}
import org.midonet.packets.util.PacketBuilder._
import org.midonet.packets.{IPv4Addr, MAC}
import org.midonet.util.concurrent._

@BenchmarkMode(Array(Mode.AverageTime))
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 5)
@Measurement(iterations = 5)
@Fork(5)
@State(Scope.Benchmark)
abstract class OvsBenchmark {

    implicit val ec = ExecutionContext.callingThread

    var con: OvsConnectionOps = _
    var datapath: Datapath = _
    var port: DpPort = _
    var anotherPort: DpPort = _
    var actions: JList[FlowAction] = _
    var otherActions: JList[FlowAction] = _
    var pkt: Packet = _
    var otherPkt: Packet = _
    var flow: Flow = _
    var otherFlow: Flow = _
    var key: FlowKey = _
    var otherKey: FlowKey = _
    val flowBytes = ByteBuffer.wrap(new Array[Byte](12))
    val otherFlowBytes = ByteBuffer.wrap(new Array[Byte](12))

    @Setup
    def createDatapath(): Unit = {
        con = new OvsConnectionOps(DatapathClient.createConnection())
        datapath = Await.result(con createDp "midonet", Duration.Inf)
        port = Await.result(con createPort(new GreTunnelPort("p"), datapath),
                            Duration.Inf)
        anotherPort = Await.result(con createPort(new VxLanTunnelPort("p"), datapath),
                                   Duration.Inf)
        actions = Arrays.asList(FlowActions.output(port.getPortNo))
        otherActions = Arrays.asList(FlowActions.output(anotherPort.getPortNo))

        val (p1, f1) = createState()
        pkt = p1
        flow = f1
        key = flow.getMatch.getKeys.get(0)

        val (p2, f2) = createState()
        otherPkt = p2
        otherFlow = f2
        otherKey = otherFlow.getMatch.getKeys.get(0)
    }

    private def createState(): (Packet, Flow) = {
        val payload = ({ eth src MAC.random dst MAC.random } <<
                       { ip4 src IPv4Addr.random dst IPv4Addr.random}).packet
        val wcmatch = FlowMatches.fromEthernetPacket(payload)
        (new Packet(payload, wcmatch), new Flow(wcmatch))
    }

    @TearDown
    def teardownDatapath(): Unit = {
        Await.result(con delPort(port, datapath), Duration.Inf)
        Await.result(con delPort(anotherPort, datapath), Duration.Inf)
        Await.result(con delDp "midonet", Duration.Inf)
    }

    @Setup(Level.Iteration)
    def doGc(): Unit = {
        System.gc()
    }
}

class PacketExecute extends OvsBenchmark {

    @Benchmark
    def packetExecute(): Unit =
        con firePacket (pkt, actions, datapath)
}

class FlowCreate extends OvsBenchmark {

    @Benchmark
    def flowCreate(): Unit = {
        ThreadLocalRandom.current().nextBytes(flowBytes.array())
        flowBytes.clear()
        key deserializeFrom flowBytes
        con.ovsCon flowsCreate (datapath, flow)
    }
}

class FlowDelete extends OvsBenchmark {

    @Benchmark
    def flowDeleteWithCallback(): Flow =
        Await.result(con delFlow (flow, datapath), Duration.Inf)

    @Benchmark
    def flowDelete(): Unit =
        con.ovsCon flowsDelete (datapath, flow.getMatch.getKeys, null)

    @Setup(Level.Invocation)
    def createFlow(): Unit =
        Await.result(con createFlow (flow, datapath), Duration.Inf)
}

class TwoChannels extends OvsBenchmark {

    var otherCon: OvsConnectionOps = _

    @Setup
    def createNewCon(): Unit =
        otherCon = new OvsConnectionOps(DatapathClient.createConnection())
}

@Threads(2)
class ConcurrentPacketExecuteFlowCreate extends TwoChannels {

    @Benchmark
    @Group("conc")
    def createFlow(): Unit = {
        ThreadLocalRandom.current().nextBytes(flowBytes.array())
        flowBytes.clear()
        key deserializeFrom flowBytes
        con.ovsCon flowsCreate (datapath, flow)
    }

    @Benchmark
    @Group("conc")
    def executePacket(): Unit =
        otherCon firePacket (pkt, otherActions, datapath)
}

@Threads(2)
class ConcurrentFlowCreate extends TwoChannels {

    @Benchmark
    @Group("flow")
    def createFlow1(): Unit = {
        ThreadLocalRandom.current().nextBytes(flowBytes.array())
        flowBytes.clear()
        key deserializeFrom flowBytes
        con.ovsCon flowsCreate (datapath, flow)
    }

    @Benchmark
    @Group("flow")
    def createFlow2(): Unit = {
        ThreadLocalRandom.current().nextBytes(otherFlowBytes.array())
        otherFlowBytes.clear()
        otherKey deserializeFrom otherFlowBytes
        otherCon.ovsCon flowsCreate (datapath, otherFlow)
    }
}

@Threads(2)
class ConcurrentPacketExecute extends TwoChannels {

    @Benchmark
    @Group("packet")
    def executePacket1(): Unit =
        con firePacket (pkt, actions, datapath)

    @Benchmark
    @Group("packet")
    def executePacket2(): Unit =
        otherCon firePacket (otherPkt, otherActions, datapath)
}
