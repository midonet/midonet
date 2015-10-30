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

package org.midonet.odp

import java.nio.ByteBuffer
import java.{util => ju}
import java.util.concurrent.TimeUnit

import scala.collection.JavaConversions._
import scala.util.control.NonFatal

import org.openjdk.jmh.annotations._
import rx.Observer

import org.midonet.ErrorCode
import org.midonet.netlink._
import org.midonet.netlink.exceptions.NetlinkException
import org.midonet.odp.flows._
import org.midonet.odp.OpenVSwitch.Flow.{Attr => FlowAttr}
import org.midonet.odp.OpenVSwitch.FlowKey.{Attr => KeyAttr}
import org.midonet.odp.ports.NetDevPort
import org.midonet.odp.util.TapWrapper
import org.midonet.packets.util.PacketBuilder._
import org.midonet.packets.{IPv4Addr, MAC}
import org.midonet.util.concurrent.NanoClock

object OvsBenchmark {
    val dpName = "bench"
    val tapName = "bench-test-tap"
    val portNumber = 10

    val channel = new NetlinkChannelFactory().create(blocking = true)
    val families = OvsNetlinkFamilies.discover(channel)
    val protocol = new OvsProtocol(channel.getLocalAddress.getPid,
                                   OvsBenchmark.families)
    val writer = new NetlinkWriter(channel)
    val reader = new NetlinkReader(channel)

    def createDp(buf: ByteBuffer): Datapath = {
        protocol.prepareDatapathCreate(dpName, buf)
        NetlinkUtil.rpc(buf, writer, reader, Datapath.buildFrom)
    }

    def deleteDp(buf: ByteBuffer): Datapath = {
        protocol.prepareDatapathDel(0, dpName, buf)
        NetlinkUtil.rpc(buf, writer, reader, Datapath.buildFrom)
    }

    @State(Scope.Benchmark)
    class DatapathState {
        private val buf = BytesUtil.instance.allocateDirect(512)
        private var tapWrapper: TapWrapper = _
        var datapath: Datapath = _

        @Setup
        def createDatapath(): Unit = {
            datapath = try {
                createDp(buf)
            } catch {
                case e: NetlinkException if e.getErrorCodeEnum == ErrorCode.EEXIST =>
                    deleteDp(buf)
                    createDp(buf)
            }

            tapWrapper = try {
                new TapWrapper(tapName)
            } catch { case NonFatal(e) =>
                new TapWrapper(tapName, false)
            }
            val tapDpPort = new NetDevPort(tapWrapper.getName, portNumber)
            protocol.prepareDpPortCreate(datapath.getIndex, tapDpPort, buf)
            NetlinkUtil.rpc(buf, writer, reader, DpPort.buildFrom)
        }

        @TearDown
        def teardownDatapath(): Unit = {
            val stats = deleteDp(buf).getStats
            println(s"Flows: ${stats.getFlows}")
            tapWrapper.remove()
        }
    }

    object FlowHolder {
        def findEthSrc(buf: ByteBuffer): Int = {
            val limit = buf.limit()
            var index = 0
            buf.position(NetlinkMessage.GENL_HEADER_SIZE)
            buf.getInt // DP id
            NetlinkMessage.scanAttributes(buf, new AttributeHandler {
                override def use(buffer: ByteBuffer, id: Short): Unit =
                    if (NetlinkMessage.unnest(id) == FlowAttr.Key) {
                        NetlinkMessage.scanAttributes(buffer, new AttributeHandler() {
                            override def use(buffer: ByteBuffer, id: Short): Unit =
                                if (id == KeyAttr.Ethernet)
                                    index = buffer.position() + 4
                        })
                    }
            })
            buf.limit(limit)
            buf.position(limit)
            index
        }
    }

    @State(Scope.Thread)
    class FlowHolder {
        private var curEth = 0
        private var ethSrcIdx = 0
        val flowBuf = BytesUtil.instance.allocateDirect(512)

        val payload = (
                { eth src MAC.fromAddress(new Array[Byte](6)) dst MAC.random } <<
                { ip4 src IPv4Addr.random dst IPv4Addr.random} <<
                { tcp src 80 dst 1001 }).packet
        val wcmatch = FlowMatches.fromEthernetPacket(payload)
        val actions: ju.List[FlowAction] = List(FlowActions.output(portNumber))

        @Setup(Level.Trial)
        def setupFlow(dp: DatapathState): Unit = {
            protocol.prepareFlowCreate(dp.datapath.getIndex, wcmatch.getKeys,
                                       actions, null, flowBuf)
            ethSrcIdx = FlowHolder.findEthSrc(flowBuf)
        }

        @Setup(Level.Invocation)
        def setupUniqueness(): Unit = {
            curEth += 1
            flowBuf.flip()
            flowBuf.putInt(ethSrcIdx, curEth)
        }
    }

    @State(Scope.Thread)
    class FlowDeletionHolder extends FlowHolder {
        private var curEth = 0
        private var ethSrcIdx = 0
        val flowDelBuf = BytesUtil.instance.allocateDirect(512)

        override def setupFlow(dp: DatapathState): Unit = {
            super.setupFlow(dp)
            protocol.prepareFlowDelete(dp.datapath.getIndex, wcmatch.getKeys, flowDelBuf)
            ethSrcIdx = FlowHolder.findEthSrc(flowDelBuf)
        }

        @Setup(Level.Invocation)
        def setupDelUniqueness(): Unit = {
            curEth += 1
            flowDelBuf.flip()
            flowDelBuf.putInt(ethSrcIdx, curEth)
        }
    }
}

@BenchmarkMode(Array(Mode.AverageTime))
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 5)
@Measurement(iterations = 5)
@Fork(5)
@State(Scope.Benchmark)
class PacketExecute {
    import OvsBenchmark._
    var pktExec = BytesUtil.instance.allocateDirect(4 * 1024)

    @Setup
    def createPacket(dp: DatapathState): Unit = {
        val payload = ({ eth src MAC.random dst MAC.random } <<
                       { ip4 src IPv4Addr.random dst IPv4Addr.random} <<
                       { tcp src 80 dst 1001 }).packet
        val wcmatch = FlowMatches.fromEthernetPacket(payload)
        val pkt = new Packet(payload, wcmatch)
        val actions = List[FlowAction](FlowActions.output(portNumber))
        protocol.preparePacketExecute(dp.datapath.getIndex, pkt, actions, pktExec)
        pktExec.position(pktExec.limit())
    }

    @Setup(Level.Invocation)
    def prepareBuffer(): Unit =
        pktExec.flip()

    @Benchmark
    def packetExecute(): Int =
        writer.write(pktExec)
}

@BenchmarkMode(Array(Mode.AverageTime))
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 5)
@Measurement(iterations = 5)
@Fork(5)
@State(Scope.Benchmark)
class FlowCreate {
    import OvsBenchmark._

    @Benchmark
    def createFlow(holder: FlowHolder): Int =
        writer.write(holder.flowBuf)
}

@BenchmarkMode(Array(Mode.AverageTime))
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 5)
@Measurement(iterations = 5)
@Fork(5)
@State(Scope.Benchmark)
class FlowCreateAndDelete {
    import OvsBenchmark._

    var buf = BytesUtil.instance.allocateDirect(4 * 1024)

    @Setup(Level.Invocation)
    def clearBuffer(): Unit =
        buf.clear()

    @Benchmark
    def createFlow(holder: FlowDeletionHolder): Int = {
        writer.write(holder.flowBuf)
        writer.write(holder.flowDelBuf)
        reader.read(buf)
    }
}

@BenchmarkMode(Array(Mode.AverageTime))
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 5)
@Measurement(iterations = 5)
@Fork(5)
@State(Scope.Benchmark)
class FlowCreateAndDeleteWithBroker {
    import OvsBenchmark._

    val broker = new NetlinkRequestBroker(
        new NetlinkBlockingWriter(channel),
        reader,
        maxPendingRequests = 1,
        maxRequestSize = 1024,
        readBuf = BytesUtil.instance.allocateDirect(4 * 1024),
        clock = NanoClock.DEFAULT)
    val obs = new Observer[ByteBuffer]() {
        override def onCompleted(): Unit = { }
        override def onError(e: Throwable): Unit = { }
        override def onNext(t: ByteBuffer): Unit = { }
    }

    @Benchmark
    def createFlow(holder: FlowDeletionHolder): Int = {
        writer.write(holder.flowBuf)
        val seq = broker.nextSequence()
        val buf = broker.get(seq)
        buf.put(holder.flowDelBuf)
        buf.flip()
        broker.publishRequest(seq, obs)
        broker.writePublishedRequests()
        broker.readReply()
    }
}

@Threads(2)
class ConcurrentFlowCreate2 extends FlowCreate
