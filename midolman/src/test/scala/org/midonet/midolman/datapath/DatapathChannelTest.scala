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

package org.midonet.midolman.datapath

import java.nio.ByteBuffer
import java.nio.channels.SelectionKey
import java.util.concurrent.{TimeUnit, LinkedBlockingQueue}
import java.util.{UUID, ArrayList}

import akka.testkit.TestProbe
import com.lmax.disruptor.{SequenceBarrier, RingBuffer}
import org.jctools.queues.SpscArrayQueue
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest.concurrent.Eventually._

import org.midonet.midolman.datapath.DisruptorDatapathChannel.PacketContextHolder
import org.midonet.midolman.DatapathStateDriver
import org.midonet.midolman.flows.{FlowOperation, ManagedFlow}
import org.midonet.midolman.util.{MockSelector, MockNetlinkChannelFactory, MidolmanSpec}
import org.midonet.netlink.{BytesUtil, NLMessageType, NetlinkMessage}
import org.midonet.odp._
import org.midonet.odp.Datapath.{Stats, MegaflowStats}
import org.midonet.odp.family.{DatapathFamily, FlowFamily, PacketFamily, PortFamily}
import org.midonet.odp.flows.{FlowKey, FlowKeys, FlowAction, FlowActions}
import org.midonet.packets.util.PacketBuilder._
import org.midonet.packets.{Ethernet, IPv4Addr, MAC}
import org.midonet.util.collection.ArrayObjectPool
import org.midonet.util.concurrent.{BackChannelEventProcessor, EventPollerHandlerAdapter, AggregateEventPollerHandler}

@RunWith(classOf[JUnitRunner])
class DatapathChannelTest extends MidolmanSpec {

    val factory = new MockNetlinkChannelFactory
    val nlChannel = factory.channel
    nlChannel.setPid(10)

    val capacity = 128

    val ovsFamilies = new OvsNetlinkFamilies(new DatapathFamily(1), new PortFamily(2),
                                             new FlowFamily(3), new PacketFamily(4), 5, 6)
    val datapathId = 11
    val datapath = new Datapath(datapathId, "midonet",
                                new Stats(0, 0, 0, 0), new MegaflowStats(0, 0))
    val ringBuffer = RingBuffer.createSingleProducer[PacketContextHolder](
        DisruptorDatapathChannel.Factory, capacity)
    var fp: FlowProcessor = _
    var processor: BackChannelEventProcessor[PacketContextHolder] = _
    var barrier: SequenceBarrier = _
    var dpChannel: DisruptorDatapathChannel = _

    val ethernet: Ethernet = ({ eth src MAC.random() dst MAC.random() } <<
                              { ip4 src IPv4Addr.random dst IPv4Addr.random } <<
                              payload(Array[Byte](0))).packet

    val actions = new ArrayList[FlowAction]()

    {
        actions.add(FlowActions.output(1))
    }

    override def beforeTest(): Unit = {
        fp = new FlowProcessor(
            new DatapathStateDriver(datapath), ovsFamilies, maxPendingRequests = 1024,
            maxRequestSize = 2048, factory, factory.selectorProvider,
            simBackChannel, clock)
        processor = new BackChannelEventProcessor[PacketContextHolder](
            ringBuffer,
            new AggregateEventPollerHandler(
                fp,
                new EventPollerHandlerAdapter(new PacketExecutor(
                    new DatapathStateDriver(datapath), ovsFamilies, 1, 0, factory, metrics))),
            fp)
        barrier = ringBuffer.newBarrier(processor.getSequence)
        dpChannel = new DisruptorDatapathChannel(ringBuffer, Array(processor))
        dpChannel.start()
    }

    override def afterTest(): Unit = {
        dpChannel.stop()
    }

    feature ("DatapathChannel handles the simulation's output") {
        scenario ("Can execute packets") {
            val context = packetContextFor(ethernet, UUID.randomUUID())
            context.packetActions.addAll(actions)
            dpChannel.handoff(context)

            barrier.waitFor(0)
            nlChannel.packetsWritten.get() should be (1)

            val bb = nlChannel.written.poll()
            bb.getInt(NetlinkMessage.NLMSG_PID_OFFSET) should be (10)
            bb.position(NetlinkMessage.GENL_HEADER_SIZE)
            bb.getInt() should be (datapathId)

            attributeListShouldMatch(bb, OpenVSwitch.Packet.Attr.Key,
                                     flowMatchParser, context.origMatch)
            attributeListShouldMatch(bb, OpenVSwitch.Packet.Attr.Actions,
                                     FlowActions.reader.deserializeFrom, actions)
            val attrLen = bb.getShort
            NetlinkMessage.unnest(bb.getShort) should be (OpenVSwitch.Packet.Attr.Packet)
            val next = bb.position() + attrLen - 4
            bb.limit(next)
            new Ethernet().deserialize(bb) should be (ethernet)
        }

        scenario ("Can create flows") {
            val context = packetContextFor(ethernet, UUID.randomUUID())
            context.flowActions.addAll(actions)
            context.flow = new ManagedFlow(null)
            dpChannel.handoff(context)

            barrier.waitFor(0)
            nlChannel.packetsWritten.get() should be (1)

            val bb = nlChannel.written.poll()
            log.debug("Messages: " + nlChannel.written.size())
            bb.getInt(NetlinkMessage.NLMSG_PID_OFFSET) should be (10)
            bb.position(NetlinkMessage.GENL_HEADER_SIZE)
            bb.getInt() should be (datapathId)

            attributeListShouldMatch(bb, OpenVSwitch.Flow.Attr.Key,
                                     flowMatchParser, context.origMatch)
            attributeListShouldMatch(bb, OpenVSwitch.Flow.Attr.Actions,
                                     FlowActions.reader.deserializeFrom, actions)
        }

        scenario ("Channel is bounded and thread spins when ring buffer is full") {
            var i = 0
            val context = packetContextFor(ethernet, UUID.randomUUID())
            context.packetActions.addAll(actions)
            while (i < 10000) {
                dpChannel.handoff(context)
                i += 1
                i - nlChannel.packetsWritten.get() should be <= capacity
            }
        }

        scenario ("Can delete flows only after they have been created") {
            val context = packetContextFor(ethernet, UUID.randomUUID())
            context.flowActions.addAll(actions)
            context.flow = new ManagedFlow(null)
            dpChannel.handoff(context)

            barrier.waitFor(0)
            nlChannel.packetsWritten.get() should be (1)

            context.flow.sequence should be (0)

            val flowDelete = new FlowOperation(TestProbe().ref,
                                               new ArrayObjectPool(0, _ => null),
                                               new SpscArrayQueue(16))
            val managedFlow = new ManagedFlow(null)
            managedFlow.flowMatch.reset(context.origMatch)
            flowDelete.reset(FlowOperation.DELETE, managedFlow, retries = 0)
            fp.tryEject(sequence = 1, datapathId, managedFlow.flowMatch,
                        flowDelete) should be (false)

            nlChannel.packetsWritten.get() should be (1)

            context.flow.sequence = 1
            dpChannel.handoff(context)

            barrier.waitFor(1)
            nlChannel.packetsWritten.get() should be (2)

            fp.tryEject(1, datapathId, managedFlow.flowMatch,
                        flowDelete) should be (true)

            eventually {
                nlChannel.packetsWritten.get() should be (3)
            }
        }

        scenario ("Create flow errors don't interfere with pending delete requests") {
            val context = packetContextFor(ethernet, UUID.randomUUID())
            context.flowActions.addAll(actions)
            context.flow = new ManagedFlow(null)
            dpChannel.handoff(context)

            barrier.waitFor(0)
            nlChannel.packetsWritten.get() should be (1)

            val seq = context.flow.sequence
            seq should be (0)
            val queue = new LinkedBlockingQueue[FlowOperation]()
            val flowDelete = new FlowOperation(TestProbe().ref,
                                               new ArrayObjectPool(0, _ => null),
                                               queue)
            val managedFlow = new ManagedFlow(null)
            managedFlow.flowMatch.reset(context.origMatch)
            flowDelete.reset(FlowOperation.DELETE, managedFlow, retries = 0)
            fp.tryEject(seq, datapathId, managedFlow.flowMatch,
                        flowDelete) should be (true)

            // Lets fail the create and complete the delete
            // If the test fails, maybe the order of selection in FlowProcessor changed
            val createReplyBuf = BytesUtil.instance.allocate(32)
            var size = NetlinkMessage.NLMSG_ERROR_SIZE + NetlinkMessage.NLMSG_ERROR_HEADER_SIZE
            NetlinkMessage.writeHeader(
                createReplyBuf, size, NLMessageType.ERROR, 0, seq.toInt, 0, 0, 0)
            createReplyBuf.putInt(NetlinkMessage.NLMSG_ERROR_OFFSET, 0)
            createReplyBuf.limit(size)
            nlChannel.toRead.offer(createReplyBuf)

            val deleteReplyBuf = BytesUtil.instance.allocate(64)
            deleteReplyBuf.position(NetlinkMessage.GENL_HEADER_SIZE)
            deleteReplyBuf.putInt(0) // dp index
            deleteReplyBuf.putShort(12)
            deleteReplyBuf.putShort(OpenVSwitch.Flow.Attr.Used)
            deleteReplyBuf.putLong(10)
            size = deleteReplyBuf.position()
            deleteReplyBuf.flip()
            NetlinkMessage.writeHeader(deleteReplyBuf, size,
                (NLMessageType.NLMSG_MIN_TYPE + 1).toShort, 0, seq.toInt, 0, 0, 0)
            nlChannel.toRead.offer(deleteReplyBuf)

            factory.selectorProvider.selector.asInstanceOf[MockSelector]
                .makeReady(SelectionKey.OP_READ, 2)

            queue.poll(1, TimeUnit.SECONDS) should not be null
            flowDelete.failure should be (null)
            flowDelete.flowMetadata.getLastUsedMillis should be (10)
        }
    }

    private def flowMatchParser(buf: ByteBuffer): FlowMatch = {
        val keys = new ArrayList[FlowKey](16)
        FlowKeys.buildFrom(buf, keys)
        new FlowMatch(keys)
    }

    private def attributeListShouldMatch[T](bb: ByteBuffer, key: Short,
                                            reader: ByteBuffer => T, expected: T) = {
        val attrLen = bb.getShort
        val id = NetlinkMessage.unnest(bb.getShort)
        id should be (key)
        val next = bb.position() + attrLen - 4
        val limit = bb.limit()
        bb.limit(next)
        reader(bb) should be (expected)
        bb.position(next)
        bb.limit(limit)
    }
}
