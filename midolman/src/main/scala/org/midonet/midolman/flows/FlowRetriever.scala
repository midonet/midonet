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

package org.midonet.midolman.flows

import java.nio.ByteBuffer

import org.jctools.queues.SpscArrayQueue

import com.typesafe.scalalogging.Logger
import org.midonet.netlink.exceptions.NetlinkException
import org.slf4j.LoggerFactory

import org.midonet.netlink._
import org.midonet.odp.{OvsProtocol, OvsNetlinkFamilies}
import org.midonet.util.concurrent.NanoClock
import org.midonet.util.concurrent.WakerUpper.Parkable
import rx.Observer

class FlowRetriever(val maxPendingRequests: Int,
                    clock: NanoClock,
                    channelFactory: NetlinkChannelFactory,
                    netlinkFamilies: OvsNetlinkFamilies) {
    private val log = Logger(LoggerFactory.getLogger(
        "org.midonet.datapath.flow-retriever"))

    private var datapathId: Int = _
    private val queue = new SpscArrayQueue[FlowGetCommand](maxPendingRequests)
    private val channel = channelFactory.create(blocking = true)
    private val pid = channel.getLocalAddress.getPid

    {
        log.debug(s"Created channel with pid $pid")
    }

    private val protocol: OvsProtocol = new OvsProtocol(pid, netlinkFamilies)

    private val requestBroker = new NetlinkRequestBroker(
        new NetlinkReader(channel),
        new NetlinkBlockingWriter(channel),
        maxPendingRequests,
        BytesUtil.instance.allocateDirect(8*1024),
        clock)

    @volatile private var running = true

    val retriever = new Thread("flow-retriever") with Parkable {
        override def run(): Unit = while (running) {
            try {
                retrieveFlows()
                park()
            } catch { case ignored: Throwable =>
                log.error("Continuing after uncaught exception", ignored)
            }
        }

        private def retrieveFlows(): Unit = {
            var pendingRequests = issueRequests()
            var repliesReceived = 0
            while (channel.isOpen && repliesReceived < pendingRequests) {
                if (requestBroker.readReply() > 0) {
                    repliesReceived += 1
                } else if (pendingRequests < maxPendingRequests) {
                    pendingRequests += issueRequests()
                }
            }
        }

        private def issueRequests(): Int = {
            var cmd: FlowGetCommand = null
            var pendingRequests = 0
            while ({ cmd = queue.poll; cmd } ne null ) {
                log.debug(s"Retrieving ${cmd.managedFlow}")
                val buf = cmd.prepareRequest(datapathId, protocol)
                buf.putInt(NetlinkMessage.NLMSG_PID_OFFSET, pid)
                requestBroker.writeRequest(buf, cmd)
                pendingRequests += 1
            }
            pendingRequests
        }

        override def shouldWakeUp(): Boolean =
            queue.size() > 0 || !running
    }

    def start(datapathId: Int): Unit = {
        this.datapathId = datapathId
        retriever.setDaemon(true)
        retriever.start()
    }

    def stop(): Unit = {
        running = false
        channel.close()
    }

    def size: Int = queue.size()

    def canRetrieve: Boolean = queue.size() < maxPendingRequests

    def retrieve(flowGet: FlowGetCommand): Boolean = queue.offer(flowGet)
}
