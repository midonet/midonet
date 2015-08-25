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

import java.{util => ju}
import java.util.concurrent.{ThreadLocalRandom, TimeUnit}

import scala.collection.JavaConversions._
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext}

import org.openjdk.jmh.annotations._

import org.midonet.netlink.{NetlinkChannel, NetlinkWriter, NetlinkChannelFactory, BytesUtil}
import org.midonet.odp.flows._
import org.midonet.odp.OvsBenchmark.{FlowHolder, ChannelHolder}
import org.midonet.odp.ports.NetDevPort
import org.midonet.odp.util.TapWrapper
import org.midonet.packets.util.PacketBuilder._
import org.midonet.packets.{TCP, IPv4Addr, MAC}
import org.midonet.util.concurrent._

object OvsBenchmark {
    val families = {
        val channel = new NetlinkChannelFactory().create(blocking = true)
        val families = OvsNetlinkFamilies.discover(channel)
        channel.close()
        families
    }

    @State(Scope.Benchmark)
    class ChannelHolder() {
        var channel: NetlinkChannel = _
        var protocol: OvsProtocol = _
        var writer: NetlinkWriter = _
        
        @Setup
        def createNewCon(): Unit = {
            channel = new NetlinkChannelFactory().create(blocking = true)
            protocol = new OvsProtocol(channel.getLocalAddress.getPid,
                                              OvsBenchmark.families)
            writer = new NetlinkWriter(channel)
        }

        @TearDown
        def destroyCon(): Unit =
            channel.close()
    }

    @State(Scope.Thread)
    class FlowHolder extends ChannelHolder {
        val rand = ThreadLocalRandom.current()
        val ethKey = new FlowKeyEthernet(new Array[Byte](6), new Array[Byte](6))
        val keys: ju.List[FlowKey] = List(
                new FlowKeyInPort(0),
                ethKey,
                new FlowKeyIPv4(rand.nextInt(), rand.nextInt(), TCP.PROTOCOL_NUMBER,
                                0, -1, 0),
                new FlowKeyTCP(rand.nextInt() & 0xffff, rand.nextInt() & 0xffff))
        val actions: ju.List[FlowAction] = List(FlowActions.output(1))
        val flowBuf = BytesUtil.instance.allocateDirect(2 * 1024)

        @Setup(Level.Invocation)
        def setup(): Unit = {
            rand.nextBytes(ethKey.eth_src)
            rand.nextBytes(ethKey.eth_dst)
            flowBuf.clear()
            protocol.prepareFlowCreate(0, keys, actions, null, flowBuf)
        }
    }
}

@BenchmarkMode(Array(Mode.AverageTime))
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 5)
@Measurement(iterations = 5)
@Fork(5)
@State(Scope.Benchmark)
abstract class OvsBenchmark {
    implicit val ec = ExecutionContext.callingThread

    var ops: OvsConnectionOps = _
    var datapath: Datapath = _
    var tapWrapper: TapWrapper = _
    var port: DpPort = _

    @Setup
    def createDatapath(): Unit = {
        ops = new OvsConnectionOps(DatapathClient.createConnection())
        datapath = Await.result(ops createDp "midonet", Duration.Inf)
        tapWrapper = new TapWrapper("odp-test-tap")
        val tapDpPort = new NetDevPort(tapWrapper.getName)
        port = Await.result(ops createPort(tapDpPort, datapath), 1 second)
    }

    @TearDown
    def teardownDatapath(): Unit = {
        Await.result(ops delPort(port, datapath), Duration.Inf)
        Await.result(ops delDp "midonet", Duration.Inf)
        tapWrapper.remove()
    }
}

class PacketExecute extends OvsBenchmark {
    var pktExec = BytesUtil.instance.allocateDirect(4 * 1024)

    @Setup
    def createPacket(channel: ChannelHolder): Unit = {
        val payload = ({ eth src MAC.random dst MAC.random } <<
                       { ip4 src IPv4Addr.random dst IPv4Addr.random} <<
                       { tcp src 80 dst 1001 }).packet
        val wcmatch = FlowMatches.fromEthernetPacket(payload)
        val pkt = new Packet(payload, wcmatch)
        val actions = List[FlowAction](FlowActions.output(port.getPortNo))
        channel.protocol.preparePacketExecute(datapath.getIndex, pkt, actions, pktExec)
    }

    @Benchmark
    def packetExecute(channel: ChannelHolder): Int =
        channel.writer.write(pktExec)
}

class FlowCreate extends OvsBenchmark {

    @Benchmark
    def createFlow(holder: FlowHolder): Int =
        holder.writer.write(holder.flowBuf)
}

@Threads(2)
class ConcurrentFlowCreate2 extends FlowCreate

@Threads(4)
class ConcurrentFlowCreate4 extends FlowCreate

@Threads(8)
class ConcurrentFlowCreate8 extends FlowCreate
