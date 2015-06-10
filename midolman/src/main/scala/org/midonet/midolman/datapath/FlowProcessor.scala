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

import com.lmax.disruptor.{Sequencer, LifecycleAware, EventPoller}
import rx.Observer

import org.slf4j.LoggerFactory
import com.typesafe.scalalogging.Logger

import org.midonet.midolman.datapath.DisruptorDatapathChannel._
import org.midonet.netlink._
import org.midonet.netlink.exceptions.NetlinkException
import org.midonet.odp.{FlowMatch, OvsNetlinkFamilies, OvsProtocol}
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
}

class FlowProcessor(families: OvsNetlinkFamilies,
                    maxPendingRequests: Int,
                    maxRequestSize: Int,
                    channelFactory: NetlinkChannelFactory,
                    clock: NanoClock)
    extends EventPoller.Handler[DatapathEvent]
    with Backchannel
    with LifecycleAware {

    import FlowProcessor._

    private val log = Logger(LoggerFactory.getLogger(
        "org.midonet.datapath.flow-processor"))

    private val channel = channelFactory.create(blocking = true)
    private val pid = channel.getLocalAddress.getPid

    {
        log.debug(s"Created channel with pid $pid")
    }

    private val writer = new NetlinkBlockingWriter(channel)
    private val broker = new NetlinkRequestBroker(
        writer, new NetlinkReader(channel), maxPendingRequests, maxRequestSize,
        BytesUtil.instance.allocateDirect(8 * 1024), clock)

    private val protocol = new OvsProtocol(pid, families)

    private var lastSequence = Sequencer.INITIAL_CURSOR_VALUE

    override def onEvent(event: DatapathEvent, sequence: Long,
                         endOfBatch: Boolean): Boolean = {
        if (event.op == FLOW_CREATE) {
            try {
                event.bb.putInt(NetlinkMessage.NLMSG_PID_OFFSET, pid)
                writer.write(event.bb)
                log.debug(s"Created flow #$sequence")
            } catch { case t: Throwable =>
                log.error(s"Failed to create flow #$sequence", t)
            }
            lastSequence = sequence
        }
        true
    }

    def capacity = broker.capacity

    def hasPendingOperations = broker.hasRequestsToWrite

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
                protocol.prepareFlowDelete(datapathId, flowMatch.getKeys, broker.get(brokerSeq))
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
                protocol.prepareFlowGet(datapathId, flowMatch, broker.get(seq))
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

    override def process(): Unit =
        broker.writePublishedRequests()

    val defaultObserver = new Observer[ByteBuffer] {
        override def onCompleted(): Unit =
            log.warn("Unexpected reply; probably the late answer of a request that timed out")

        override def onError(e: Throwable): Unit = e match {
            case ne: NetlinkException if ne.getErrorCodeEnum == ErrorCode.EEXIST =>
                log.debug("Tried to add duplicate DP flow")
            case ne: NetlinkException =>
                log.warn(s"Unexpected error ${ne.getErrorCodeEnum}; " +
                         "probably the late answer of a request that timed out")
            }

        override def onNext(t: ByteBuffer): Unit = { }
    }

    val replies = new Thread("flow-processor-replies") {
        override def run(): Unit =
            while (channel.isOpen) {
                try {
                    broker.readReply(defaultObserver)
                } catch { case t: Throwable =>
                    log.debug("Error while reading replies", t)
                }
            }
    }

    override def onStart(): Unit = {
        replies.setDaemon(true)
        replies.start()
    }

    override def onShutdown(): Unit = {
        channel.close()
        replies.interrupt()
    }
}
