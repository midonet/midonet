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

import java.nio.channels._
import java.nio.channels.spi.SelectorProvider
import java.nio.{BufferOverflowException, ByteBuffer}
import java.util.ArrayList

import scala.util.control.NonFatal

import com.lmax.disruptor.{EventPoller, LifecycleAware, Sequencer}
import com.typesafe.scalalogging.Logger

import org.slf4j.LoggerFactory

import rx.Observer

import org.midonet.midolman.SimulationBackChannel.{BackChannelMessage, Broadcast}
import org.midonet.midolman.datapath.DisruptorDatapathChannel._
import org.midonet.midolman.simulation.PacketContext
import org.midonet.midolman.{DatapathState, SimulationBackChannel}
import org.midonet.midolman.monitoring.metrics.DatapathMetrics
import org.midonet.netlink._
import org.midonet.netlink.exceptions.NetlinkException
import org.midonet.odp._
import org.midonet.odp.flows.{FlowAction, FlowKey}
import org.midonet.sixwind.SixWind
import org.midonet.util.concurrent.{DisruptorBackChannel, NanoClock}
import org.midonet.{ErrorCode, Util}

object FlowProcessor {
    private val unsafe = Util.getUnsafe

    /**
     * Used for unsafe access to the lastSequence field, so we can do a volatile
     * read on the writer thread while avoiding doing a volatile write to it
     * from the producer thread.
     */
    private val sequenceAddress = unsafe.objectFieldOffset(
        classOf[FlowProcessor].getDeclaredField("lastSequence"))

    private val MAX_BUF_CAPACITY = 4 * 1024 * 1024

    /**
      * A flow back-channel message.
      */
    trait FlowMessage extends BackChannelMessage with Broadcast
    /**
      * Back-channel message for a duplicate flow.
      */
    case class DuplicateFlow(index: Int) extends FlowMessage
    /**
      * Back-channel message for a flow error.
      */
    case class FlowError(index: Int) extends FlowMessage

}

class FlowProcessor(dpState: DatapathState,
                    families: OvsNetlinkFamilies,
                    maxPendingRequests: Int,
                    maxRequestSize: Int,
                    channelFactory: NetlinkChannelFactory,
                    selectorProvider: SelectorProvider,
                    backChannel: SimulationBackChannel,
                    datapathMetrics: DatapathMetrics,
                    clock: NanoClock)
    extends EventPoller.Handler[PacketContextHolder]
    with DisruptorBackChannel
    with LifecycleAware {

    import FlowProcessor._

    private val log = Logger(LoggerFactory.getLogger(
        "org.midonet.datapath.flow-processor"))

    private val datapathId = dpState.datapath.getIndex
    private val supportsMegaflow = dpState.datapath.supportsMegaflow()

    private var writeBuf = BytesUtil.instance.allocateDirect(64 * 1024)
    private val selector = selectorProvider.openSelector()
    private val createChannel = channelFactory.create(blocking = false)
    private val createChannelPid = createChannel.getLocalAddress.getPid
    private val createProtocol = new OvsProtocol(createChannelPid, families)
    private val brokerChannel = channelFactory.create(blocking = false)
    private val brokerChannelPid = brokerChannel.getLocalAddress.getPid
    private val brokerProtocol = new OvsProtocol(brokerChannelPid, families)
    private val sixwind = SixWind.create()

    {
        log.debug(s"Created write channel with pid $createChannelPid")
        log.debug(s"Created delete channel with pid $brokerChannelPid")
        sixwind.flush()
    }

    private val writer = new NetlinkBlockingWriter(createChannel)
    private val broker = new NetlinkRequestBroker(
        new NetlinkBlockingWriter(brokerChannel),
        new NetlinkReader(brokerChannel),
        maxPendingRequests,
        maxRequestSize,
        BytesUtil.instance.allocateDirect(64 * 1024),
        clock)
    private val timeoutMillis = broker.timeout.toMillis

    private val flowMask = new FlowMask()

    private var lastSequence = Sequencer.INITIAL_CURSOR_VALUE

    override def onEvent(event: PacketContextHolder, sequence: Long,
                         endOfBatch: Boolean): Boolean = {
        val context = event.flowCreateRef
        log.debug(s"Packet context event $context with flow ${context.flow}")
        event.flowCreateRef = null
        if (context.flow ne null) {
            // We use the same index for linked flows: if there is a problem
            // with any of them, we remove both.
            val index = context.flow.mark
            try {
                createFlow(context.origMatch, context.flowActions, context, index)
                datapathMetrics.flowsCreated.mark()
                if (context.isRecirc) {
                    // Note we created the after-recirc flow first, so new
                    // packets will still go to midolman before recirculation.
                    createFlow(
                        context.recircMatch, context.recircFlowActions, context, index)
                    datapathMetrics.flowsCreated.mark()
                }
            } catch { case t: Throwable =>
                context.log.error("Failed to create datapath flow", t)
            }

            // Note: user -> kernel netlink communication is synchronous.
            // At this point, our createFlow requests above has been
            // processed by the kernel and it's safe to update lastSequence.
            lastSequence = sequence
        }
        context.setFlowProcessed()
        true
    }

    private def createFlow(flowMatch: FlowMatch, actions: ArrayList[FlowAction],
                           context: PacketContext, index: Int): Unit = {
        val mask = if (supportsMegaflow) {
            flowMask.clear()
            flowMask.calculateFor(flowMatch, actions)
            context.log.debug(s"Applying mask $flowMask")
            flowMask
        } else null
        writeFlow(datapathId, flowMatch.getKeys, actions, mask, index)
        context.log.debug(s"Created datapath flow for $flowMatch")
    }

    private def writeFlow(
            datapathId: Int,
            keys: ArrayList[FlowKey],
            actions: ArrayList[FlowAction],
            mask: FlowMask,
            index: Int): Unit =
        try {
            createProtocol.prepareFlowCreate(
                datapathId, keys, actions, mask, writeBuf)
            writeBuf.putInt(NetlinkMessage.NLMSG_SEQ_OFFSET, index)
            writer.write(writeBuf)
            writeBuf.rewind()
            sixwind.processFlow(writeBuf, writeBuf.limit())
        } catch { case e: BufferOverflowException =>
            val capacity = writeBuf.capacity()
            if (capacity >= MAX_BUF_CAPACITY)
                throw e
            val newCapacity = capacity * 2
            writeBuf = BytesUtil.instance.allocateDirect(newCapacity)
            log.debug(s"Increasing buffer size to $newCapacity")
            writeFlow(datapathId, keys, actions, mask, index)
        } finally {
            writeBuf.clear()
        }

    def capacity = broker.capacity

    /**
     * Tries to eject a flow only if the corresponding Disruptor sequence is
     * greater than the one specified, meaning that the corresponding flow
     * create operation hasn't been completed yet.
     */
    def tryEject(sequence: Long, datapathId: Int, flowMatch: FlowMatch,
                 obs: Observer[ByteBuffer]): Boolean = {
        var brokerSeq = 0L
        val disruptorSeq = unsafe.getLongVolatile(this, sequenceAddress)
        if (disruptorSeq >= sequence && { brokerSeq = broker.nextSequence()
                                          brokerSeq } != NetlinkRequestBroker.FULL) {
            try {
                val buffer = broker.get(brokerSeq)
                brokerProtocol.prepareFlowDelete(
                    datapathId, flowMatch.getKeys, buffer)
                sixwind.processFlow(buffer, buffer.limit())
                broker.publishRequest(brokerSeq, obs)
                datapathMetrics.flowsDeleted.mark()
            } catch { case e: Throwable =>
                obs.onError(e)
            }
            true
        } else {
            false
        }
    }

    def tryGet(datapathId: Int, flowMatch: FlowMatch,
               obs: Observer[ByteBuffer]): Boolean = {
        var seq = 0L
        if ({ seq = broker.nextSequence(); seq } != NetlinkRequestBroker.FULL) {
            try {
                brokerProtocol.prepareFlowGet(
                    datapathId, flowMatch, broker.get(seq))
                broker.publishRequest(seq, obs)
            } catch { case e: Throwable =>
                obs.onError(e)
            }
            true
        } else {
            false
        }
    }

    override def shouldProcess(): Boolean =
        broker.hasRequestsToWrite

    override def process(): Unit = {
        if (broker.hasRequestsToWrite) {
            val bytes = broker.writePublishedRequests()
            log.debug(s"Wrote flow deletion requests ($bytes bytes)")
        }
    }

    val handleDeleteError = new Observer[ByteBuffer] {
        override def onCompleted(): Unit =
            log.warn("Unexpected reply to flow deletion; probably the late " +
                     "answer of a request that timed out")

        override def onError(e: Throwable): Unit = {
            datapathMetrics.flowDeleteErrors.mark()
            e match {
                case ne: NetlinkException =>
                    log.warn(s"Unexpected flow deletion error ${ne.getErrorCodeEnum}; " +
                                 "probably the late answer of a request that timed out")
                case NonFatal(nf) =>
                    log.error("Unexpected error when deleting a flow", nf)
            }
        }

        override def onNext(t: ByteBuffer): Unit = { }
    }

    private def handleCreateError(reader: NetlinkReader, buf: ByteBuffer): Unit =
        try {
            reader.read(buf)
        } catch {
            case ne: NetlinkException if ne.getErrorCodeEnum == ErrorCode.EEXIST =>
                datapathMetrics.flowCreateDupes.mark()
                log.debug("Tried to add duplicate DP flow with index " +
                          s"0x${Integer.toHexString(ne.seq)}")
                backChannel.tell(DuplicateFlow(ne.seq))
            case ne: NetlinkException =>
                datapathMetrics.flowCreateErrors.mark()
                log.warn("Failed to create flow with index " +
                         s"0x${Integer.toHexString(ne.seq)}", ne)
                backChannel.tell(FlowError(ne.seq))
            case NonFatal(e) =>
                datapathMetrics.flowCreateErrors.mark()
                log.error("Unexpected error when creating a flow")
        } finally {
            buf.clear()
        }

    private val replies = new Thread("flow-processor-errors") {
        override def run(): Unit = {
            val reader = new NetlinkReader(createChannel)
            val createErrorsBuffer = BytesUtil.instance.allocateDirect(64 * 1024)
            while (createChannel.isOpen && brokerChannel.isOpen) {
                val createKey = createChannel.register(selector, SelectionKey.OP_READ)
                val deleteKey = brokerChannel.register(selector, SelectionKey.OP_READ)
                try {
                    if (selector.select(timeoutMillis) > 0) {
                        if (createKey.isReadable)
                            handleCreateError(reader, createErrorsBuffer)
                        if (deleteKey.isReadable)
                            broker.readReply(handleDeleteError)
                        selector.selectedKeys().clear()
                    } else {
                        broker.timeoutExpiredRequests()
                    }
                } catch {
                    case ignored @ (_: InterruptedException |
                                    _: ClosedChannelException |
                                    _: ClosedByInterruptException|
                                    _: AsynchronousCloseException) =>
                    case NonFatal(t) =>
                        log.debug("Error while reading replies", t)
                }
            }
        }
    }

    override def onStart(): Unit = {
        replies.setDaemon(true)
        replies.start()
    }

    override def onShutdown(): Unit = {
        createChannel.close()
        brokerChannel.close()
        selector.wakeup()
    }
}
