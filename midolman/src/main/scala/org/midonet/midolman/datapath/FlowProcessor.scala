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

import scala.concurrent.duration._

import com.lmax.disruptor.{LifecycleAware, EventPoller, Sequencer}
import rx.Observer

import org.slf4j.LoggerFactory
import com.typesafe.scalalogging.Logger

import org.midonet.midolman.datapath.DisruptorDatapathChannel._
import org.midonet.midolman.flows.FlowEjector
import org.midonet.netlink._
import org.midonet.odp.{OvsNetlinkFamilies, OvsProtocol}
import org.midonet.util.concurrent.{Backchannel, NanoClock}

sealed class FlowProcessor(flowEjector: FlowEjector,
                           channelFactory: NetlinkChannelFactory,
                           datapathId: Int,
                           ovsFamilies: OvsNetlinkFamilies,
                           clock: NanoClock)
     extends EventPoller.Handler[DatapathEvent]
     with Backchannel
     with LifecycleAware {

    private val log = Logger(LoggerFactory.getLogger(
        "org.midonet.datapath.flow-processor"))

    private val channel = channelFactory.create(blocking = true)
    private val pid = channel.getLocalAddress.getPid

    {
        log.debug(s"Created channel with pid $pid")
    }

    private val writer = new NetlinkBlockingWriter(channel)
    private val requestReply = new NetlinkRequestBroker(
        new NetlinkReader(channel),
        writer,
        flowEjector.maxPendingRequests,
        BytesUtil.instance.allocateDirect(8*1024),
        clock,
        5 seconds)
    private val protocol = new OvsProtocol(pid, ovsFamilies)

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

    override def shouldProcess(): Boolean = {
        val flowDelete = flowEjector.peek()
        (flowDelete ne null) && flowDelete.managedFlow.flowMatch.getSequence <= lastSequence
    }

    override def process(): Unit = {
        while (shouldProcess()) {
            val flowDelete = flowEjector.poll()
            log.debug(s"Deleting flow ${flowDelete.managedFlow}")
            val buf = flowDelete.prepareRequest(datapathId, protocol)
            requestReply.writeRequest(buf, flowDelete)
        }
    }

    val defaultObserver = new Observer[ByteBuffer] {
            override def onCompleted(): Unit =
                log.warn("Unexpected reply - probably the late answer of a request that timed out")

            override def onError(e: Throwable): Unit = onCompleted()
            override def onNext(t: ByteBuffer): Unit = { }
    }

    val remover = new Thread("flow-remover") {
        override def run(): Unit =
            try {
                while (channel.isOpen) {
                    requestReply.readReply(defaultObserver)
                }
            } catch { case ignored: Throwable => }
    }

    override def onStart(): Unit = {
        remover.setDaemon(true)
        remover.start()
    }

    override def onShutdown(): Unit = {
        channel.close()
        remover.interrupt()
    }
}
