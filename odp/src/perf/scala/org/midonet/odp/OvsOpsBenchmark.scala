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

import java.util.concurrent.TimeUnit

import java.util.{Arrays, List => JList}

import scala.concurrent.duration.Duration
import scala.concurrent.{Await, ExecutionContext}

import org.openjdk.jmh.annotations._

import org.midonet.odp.flows.{FlowActions, FlowAction}
import org.midonet.odp.ports.GreTunnelPort
import org.midonet.packets.{IPv4Addr, MAC}
import org.midonet.packets.util.PacketBuilder._
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
    var actions: JList[FlowAction] = _
    var pkt: Packet = _
    var flow: Flow = _

    @Setup
    def createDatapath(): Unit = {
        con = new OvsConnectionOps(DatapathClient.createConnection())
        datapath = Await.result(con createDp "midonet", Duration.Inf)
        port = Await.result(con createPort(new GreTunnelPort("p"), datapath),
                            Duration.Inf)
        actions = Arrays.asList(FlowActions.output(port.getPortNo))
        val payload = ({ eth src MAC.random dst MAC.random } <<
                       { ip4 src IPv4Addr.random dst IPv4Addr.random}).packet
        val wcmatch = FlowMatches.fromEthernetPacket(pkt.getEthernet)
        pkt = new Packet(payload, wcmatch)
        flow = new Flow(wcmatch)
    }

    @TearDown
    def teardownDatapath(): Unit = {
        Await.result(con delPort(port, datapath), Duration.Inf)
        Await.result(con delDp "midonet", Duration.Inf)
    }
}

class PacketExecuteOvsBenchmark extends OvsBenchmark {

    @Benchmark
    def packetExecute(): Unit =
        con firePacket(pkt, actions, datapath)
}

class FlowCreateOvsBenchmark extends OvsBenchmark {

    @Benchmark
    def flowCreate(): Flow =
        Await.result(con createFlow(flow, datapath), Duration.Inf)

    @TearDown(Level.Invocation)
    def deleteFlow(): Unit =
        Await.result(con delFlow(flow, datapath), Duration.Inf)
}

class FlowDeleteOvsBenchmark extends OvsBenchmark {

    @Benchmark
    def flowDelete(): Flow =
        Await.result(con delFlow (flow, datapath), Duration.Inf)

    @Setup(Level.Invocation)
    def createFlow(): Unit =
        Await.result(con createFlow (flow, datapath), Duration.Inf)
}
