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

import java.nio.channels.SelectionKey
import java.nio.channels.spi.SelectorProvider
import java.nio.{BufferOverflowException, ByteBuffer}
import java.util.ArrayList

import com.lmax.disruptor.{EventPoller, LifecycleAware, Sequencer}
import rx.Observer

import com.typesafe.scalalogging.Logger
import org.slf4j.LoggerFactory

import org.midonet.Util
import org.midonet.midolman.datapath.DisruptorDatapathChannel._
import org.midonet.netlink._
import org.midonet.netlink.exceptions.NetlinkException
import org.midonet.odp.flows.{FlowAction, FlowKey}
import org.midonet.odp._
import org.midonet.util.concurrent.{Backchannel, NanoClock}

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

class FlowProcessor(datapath: Datapath,
                    families: OvsNetlinkFamilies,
                    numPartitions: Int,
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

    private val datapathId = datapath.getIndex
    private val supportsMegaflow = datapath.supportsMegaflow()

    private var writeBuf = BytesUtil.instance.allocateDirect(64 * 1024)
    private val mainChannel = channelFactory.create(blocking = true)
    private val pid = mainChannel.getLocalAddress.getPid
    private val writer = new NetlinkWriter(mainChannel)

    {
        log.debug(s"Created channel with pid $pid")
    }

    private val partitions = Math.max(1, numPartitions)

    private val brokerChannels = {
        val brokerChannels = new Array[NetlinkChannel](partitions)
        var i = 0
        while (i < partitions) {
            // The channel must be non-blocking so that one broker doesn't
            // block another.
            brokerChannels(i) = channelFactory.create(blocking = false)
            i += 1
        }
        brokerChannels
    }

    private val selector = {
        val selector = selectorProvider.openSelector()
        var i = 0
        while (i < partitions) {
            brokerChannels(i).register(selector, SelectionKey.OP_READ)
            i += 1
        }
        selector
    }

    private val brokerPids = {
        val brokerPids = new Array[Int](partitions)
        var i = 0
        while (i < partitions) {
            brokerPids(i) = brokerChannels(i).getLocalAddress.getPid
            i += 1
        }
        brokerPids
    }

    private val brokers = {
        val brokers = new Array[NetlinkRequestBroker](partitions)
        var i = 0
        while (i < partitions) {
            val channel = brokerChannels(i)
            brokers(i) = new NetlinkRequestBroker(
                new NetlinkBlockingWriter(channel),
                new NetlinkReader(channel),
                maxPendingRequests,
                maxRequestSize,
                BytesUtil.instance.allocateDirect(8 * 1024), // TODO: Reply buf size needs to be increased to match writeBuf
                clock)
            i += 1
        }
        brokers
    }

    private val protocol = new OvsProtocol(families)

    private val flowMask = new FlowMask()

    private var lastSequence = Sequencer.INITIAL_CURSOR_VALUE

    val capacity = brokers(0).capacity

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
                writeFlow(datapathId, flowMatch.getKeys,
                          context.flowActions, mask)
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
            protocol.prepareFlowCreate(pid, datapathId, keys, actions, mask, writeBuf)
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

    def hasPendingOperations: Boolean = {
        var i = 0
        while (i < brokers.length) {
            if (brokers(i).hasRequestsToWrite)
                return true
            i += 1
        }
        false
    }

    /**
     * Tries to eject a flow only if the corresponding Disruptor sequence is
     * greater than the one specified, meaning that the corresponding flow
     * create operation hasn't been completed yet.
     */
    def tryEject(
            partitionId: Int, 
            flowSequence: Long,
            datapathId: Int, 
            flowMatch: FlowMatch,
            obs: Observer[ByteBuffer]): Boolean = {
        var brokerSeq = 0
        val disruptorSeq = unsafe.getLongVolatile(this, sequenceAddress)
        val broker = brokers(partitionId)
        if (disruptorSeq >= flowSequence && { brokerSeq = broker.nextSequence()
                                              brokerSeq } != NetlinkRequestBroker.FULL) {
            try {
                val buf = broker.get(brokerSeq)
                protocol.prepareFlowDelete(
                    brokerPids(partitionId), datapathId, flowMatch.getKeys, buf)
                broker.publishRequest(brokerSeq, obs)
            } catch { case e: Throwable =>
                obs.onError(e)
            }
            true
        } else {
            false
        }
    }

    def tryGet(partitionId: Int, datapathId: Int, flowMatch: FlowMatch,
               obs: Observer[ByteBuffer]): Boolean = {
        var seq = 0
        val broker = brokers(partitionId)
        if ({ seq = broker.nextSequence(); seq } != NetlinkRequestBroker.FULL) {
            try {
                val buf = broker.get(seq)
                protocol.prepareFlowGet(
                    brokerPids(partitionId), datapathId, flowMatch, buf)
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
        hasPendingOperations

    override def process(): Unit = {
        var i = 0
        while (i < partitions) {
            brokers(i).writePublishedRequests()
            i += 1
        }
    }

    val defaultObserver = new Observer[ByteBuffer] {
        override def onCompleted(): Unit =
            log.warn("Unexpected reply; probably the late answer of a request that timed out")

        override def onError(e: Throwable): Unit = e match {
            case ne: NetlinkException if ne.getErrorCodeEnum == NetlinkException.ErrorCode.EEXIST =>
                log.debug("Tried to add duplicate DP flow")
            case ne: NetlinkException =>
                log.warn(s"Unexpected error with code ${ne.getErrorCodeEnum}; " +
                         "probably the late answer of a request that timed out")
            }

        override def onNext(t: ByteBuffer): Unit = { }
    }

    val replies = new Thread("flow-processor-replies") {
        private def channelsClosed: Boolean = {
            var i = 0
            while (i < partitions) {
                if (brokerChannels(i).isOpen)
                    return false
                i += 1
            }
            true
        }

        override def run(): Unit =
            while (!channelsClosed) {
                var i = 0
                while (i < partitions) {
                    try {
                        brokers(i).readReply(defaultObserver)
                    } catch { case t: Throwable =>
                        log.debug(s"Error while reading replies in partition $i", t)
                    }
                    i += 1
                }

                try {
                    selector.select()
                } catch { case t: Throwable =>
                    log.debug("Error while waiting for replies", t)
                }
            }
        }

    override def onStart(): Unit = {
        replies.setDaemon(true)
        replies.start()
    }

    override def onShutdown(): Unit = {
        mainChannel.close()
        var i = 0
        while (i < partitions) {
            brokerChannels(i).close()
            i += 1
        }
        selector.wakeup()
    }
}
