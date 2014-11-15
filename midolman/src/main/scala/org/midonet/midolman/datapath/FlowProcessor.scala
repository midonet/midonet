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

import com.lmax.disruptor.{EventPoller, Sequencer}

import rx.Observer

import org.slf4j.LoggerFactory
import com.typesafe.scalalogging.Logger

import org.midonet.midolman.datapath.DisruptorDatapathChannel._
import org.midonet.midolman.flows.FlowEjector
import org.midonet.netlink._
import org.midonet.netlink.exceptions.NetlinkException
import org.midonet.netlink.exceptions.NetlinkException.ErrorCode
import org.midonet.odp.{OvsNetlinkFamilies, OvsProtocol, FlowMatch}
import org.midonet.util.collection.{ArrayObjectPool, PooledObject, ObjectPool}
import org.midonet.util.concurrent.{Backchannel, NanoClock}

object FlowProcessor {
    sealed class RetryObserver(val pool: ObjectPool[RetryObserver],
                               requestReply: NetlinkRequestReply,
                               log: Logger) extends Observer[ByteBuffer]
                                            with PooledObject {

        val buf = BytesUtil.instance.allocateDirect(8*1024)
        var flowMatch: FlowMatch = _
        var retries: Int = _

        {
            clear()
        }

        def clear(): Unit = {
            flowMatch = null
            buf.clear()
            retries = 10
        }

        def makeRequest(): Unit = {
            log.debug(s"Deleting flow with match $flowMatch")
            requestReply.request(buf, this)
        }

        override def onNext(t: ByteBuffer): Unit = { }

        override def onError(e: Throwable): Unit = e match {
            case exception: NetlinkException =>
                exception.getErrorCodeEnum match {
                    case ErrorCode.ENODEV | ErrorCode.ENOENT | ErrorCode.ENXIO =>
                        onCompleted()
                    case _ if retries > 0 =>
                        retries -= 1
                        makeRequest()
                    case _ =>
                        fail(e)
                }
            case _ => fail(e)
        }

        private def fail(e: Throwable): Unit = {
            log.warn(s"Failed to delete flow with match $flowMatch", e)
            unref()
        }

        override def onCompleted(): Unit = {
            log.debug(s"Deleted flow with match $flowMatch")
            unref()
        }
    }
}

sealed class FlowProcessor(flowEjector: FlowEjector,
                          channelFactory: NetlinkChannelFactory,
                          datapathId: Int,
                          ovsFamilies: OvsNetlinkFamilies,
                          clock: NanoClock)
                     extends EventPoller.Handler[DatapathEvent]
                     with Backchannel {
    import FlowProcessor._

    // TODO: Support optional callback when log level is set to debug

    private val log = Logger(LoggerFactory.getLogger(
        "org.midonet.datapath.flow-creator"))

    private val channel = channelFactory.create(blocking = false)
    private val pid = channel.getLocalAddress.getPid
    private val writer = new NetlinkWriter(channel)
    private val blockingWriter = new NetlinkBlockingWriter(channel)
    private val requestReply = new NetlinkRequestReply(
        new NetlinkReader(channel),
        blockingWriter,
        flowEjector.maxPendingRequests,
        BytesUtil.instance.allocateDirect(8*1024),
        clock)
    private val protocol = new OvsProtocol(pid, ovsFamilies)
    private var pendingFlowDeletions = 0

    private val pool = new ArrayObjectPool[RetryObserver](
        flowEjector.maxPendingRequests,
        new RetryObserver(_, requestReply, log))

    private var lastSequence = Sequencer.INITIAL_CURSOR_VALUE

    override def onEvent(event: DatapathEvent, sequence: Long,
                         endOfBatch: Boolean): Boolean = {
        if (event.op == FLOW_CREATE) {
            try {
                event.bb.putInt(NetlinkMessage.NLMSG_PID_OFFSET, pid)
                if (writer.write(event.bb) == 0) {
                    process()
                    blockingWriter.write(event.bb)
                }
                log.debug("Created flow")
            } catch { case t: Throwable =>
                log.error("Failed to create flow", t)
            }
            if (endOfBatch) {
                lastSequence = sequence
            }
        }
        true
    }

    override def shouldProcess(): Boolean = {
        val flowMatch = flowEjector.peek()
        (flowMatch ne null) && flowMatch.getSequence <= lastSequence
    }

    val defaultObserver = new Observer[ByteBuffer] {
        override def onCompleted(): Unit = log.warn("Unexpected answer")

        override def onError(e: Throwable): Unit = log.warn("Unexpected error", e)

        override def onNext(t: ByteBuffer): Unit = log.warn("Unexpected answer")
    }

    override def process(): Unit = {
        var obs: RetryObserver = null
        while (shouldProcess() && ({ obs = pool.take; obs } ne null)) {
            val flowMatch = flowEjector.poll()
            obs.flowMatch = flowMatch
            protocol.prepareFlowDelete(datapathId, flowMatch.getKeys, obs.buf)
            obs.makeRequest()
            pendingFlowDeletions += 1
        }
        while (pendingFlowDeletions > 0 &&
               requestReply.processReply(defaultObserver) > 0) {
            pendingFlowDeletions -= 1
        }
    }
}
