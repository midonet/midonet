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

import com.lmax.disruptor.{Sequencer, LifecycleAware, EventPoller}
import org.midonet.midolman.DatapathState
import rx.Observer

import org.slf4j.LoggerFactory
import com.typesafe.scalalogging.Logger

import org.midonet.midolman.datapath.DisruptorDatapathChannel._
import org.midonet.netlink._
import org.midonet.netlink.exceptions.NetlinkException
import org.midonet.odp._
import org.midonet.odp.flows.{FlowAction, FlowKey}
import org.midonet.{ErrorCode, Util}
import org.midonet.util.concurrent.{NanoClock, Backchannel}

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
}

class FlowProcessor(dpState: DatapathState,
                    families: OvsNetlinkFamilies,
                    maxPendingRequests: Int,
                    maxRequestSize: Int,
                    channelFactory: NetlinkChannelFactory,
                    selectorProvider: SelectorProvider,
                    clock: NanoClock)
    extends EventPoller.Handler[PacketContextHolder]
    with Backchannel
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

    {
        log.debug(s"Created write channel with pid $createChannelPid")
        log.debug(s"Created delete channel with pid $brokerChannelPid")
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
        val flowMatch = context.origMatch
        event.flowCreateRef = null
        if (context.flow ne null) {
            try {
                val mask = if (supportsMegaflow) {
                    flowMask.calculateFor(flowMatch)
                    context.log.debug(s"Applying mask $flowMask")
                    flowMask
                } else null
                writeFlow(
                    datapathId, flowMatch.getKeys, context.flowActions, mask)
                context.log.debug("Created datapath flow")
            } catch { case t: Throwable =>
                context.log.error("Failed to create datapath flow", t)
            } finally {
                flowMask.clear()
                writeBuf.clear()
            }

            lastSequence = sequence
        }
        true
    }

    private def writeFlow(datapathId: Int, keys: ArrayList[FlowKey],
                          actions: ArrayList[FlowAction], mask: FlowMask): Unit =
        try {
            createProtocol.prepareFlowCreate(
                datapathId, keys, actions, mask, writeBuf)
            writer.write(writeBuf)
        } catch { case e: BufferOverflowException =>
            val capacity = writeBuf.capacity()
            if (capacity >= MAX_BUF_CAPACITY)
                throw e
            val newCapacity = capacity * 2
            writeBuf = BytesUtil.instance.allocateDirect(newCapacity)
            log.debug(s"Increasing buffer size to $newCapacity")
            writeFlow(datapathId, keys, actions, mask)
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
                brokerProtocol.prepareFlowDelete(
                    datapathId, flowMatch.getKeys, broker.get(brokerSeq))
                broker.publishRequest(brokerSeq, obs)
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

        override def onError(e: Throwable): Unit = e match {
            case ne: NetlinkException =>
                log.warn(s"Unexpected flow deletion error ${ne.getErrorCodeEnum}; " +
                         "probably the late answer of a request that timed out")
            case NonFatal(nf) =>
                log.error("Unexpected error when deleting a flow", nf)
        }

        override def onNext(t: ByteBuffer): Unit = { }
    }

    private def handleCreateError(reader: NetlinkReader, buf: ByteBuffer): Unit =
        try {
            reader.read(buf)
        } catch {
            case ne: NetlinkException if ne.getErrorCodeEnum == ErrorCode.EEXIST =>
                log.debug("Tried to add duplicate DP flow")
            case e: NetlinkException =>
                log.warn("Failed to create flow", e)
            case NonFatal(e) =>
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
