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

import com.lmax.disruptor.{LifecycleAware, EventPoller}
import rx.Observer

import org.slf4j.LoggerFactory
import com.typesafe.scalalogging.Logger

import org.midonet.midolman.datapath.DisruptorDatapathChannel._
import org.midonet.netlink._
import org.midonet.netlink.exceptions.NetlinkException
import org.midonet.odp.{FlowMatch, OvsNetlinkFamilies, OvsProtocol}
import org.midonet.Util
import org.midonet.util.concurrent.{NanoClock, Backchannel}

object FlowProcessor {
    private val unsafe = Util.getUnsafe

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
        BytesUtil.instance.allocate(8 * 1024), clock)

    private val protocol = new OvsProtocol(pid, families)

    private var lastSequence = 0L

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
        var brokerSeq = 0
        val disrutporSeq = unsafe.getLongVolatile(this, sequenceAddress)
        if (disrutporSeq >= sequence && { brokerSeq = broker.nextSequence()
                                          brokerSeq } != NetlinkRequestBroker.FULL) {
            protocol.prepareFlowDelete(datapathId, flowMatch.getKeys, broker.get(brokerSeq))
            broker.publishRequest(brokerSeq, obs)
            true
        } else {
            false
        }
    }

    def tryGet(datapathId: Int, flowMatch: FlowMatch,
               obs: Observer[ByteBuffer]): Boolean = {
        var seq = 0
        if ({ seq = broker.nextSequence(); seq } != NetlinkRequestBroker.FULL) {
            protocol.prepareFlowGet(datapathId, flowMatch, broker.get(seq))
            broker.publishRequest(seq, obs)
            true
        } else {
            false
        }
    }

    override def shouldProcess(): Boolean = {
        val res = broker.hasRequestsToWrite
        log.debug(s"should process is $res")
        res
    }

    override def process(): Unit =
        broker.writePublishedRequests()

    val defaultObserver = new Observer[ByteBuffer] {
        override def onCompleted(): Unit =
            log.warn("Unexpected reply - probably the late answer of a request that timed out")

        override def onError(e: Throwable): Unit = e match {
            case ne: NetlinkException if ne.getErrorCodeEnum == NetlinkException.ErrorCode.EEXIST =>
                log.debug("Tried to add duplicate DP flow")
            case _ =>
                onCompleted()
            }

        override def onNext(t: ByteBuffer): Unit = { }
    }

    val replies = new Thread("flow-processor-replies") {
        override def run(): Unit =
            try {
                while (channel.isOpen) {
                    broker.readReply(defaultObserver)
                }
            } catch { case ignored: Throwable => }
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
