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

import com.lmax.disruptor.EventHandler

import org.slf4j.LoggerFactory
import com.typesafe.scalalogging.Logger

import org.midonet.midolman.datapath.DisruptorDatapathChannel._
import org.midonet.netlink.{NetlinkMessage, NetlinkWriter, NetlinkChannelFactory}

sealed class PacketExecutor(numHandlers: Int, index: Int,
                            channelFactory: NetlinkChannelFactory)
    extends EventHandler[DatapathEvent] {

    // TODO: Support optional callback when log level is set to debug

    private val log = Logger(LoggerFactory.getLogger(
        s"org.midonet.datapath.packet-executor-$index"))

    private val writer = new NetlinkWriter(
        channelFactory.create(blocking = true))
    private val pid = writer.channel.getLocalAddress.getPid

    {
        log.debug(s"Created channel with pid $pid")
    }

    override def onEvent(event: DatapathEvent, sequence: Long,
                         endOfBatch: Boolean): Unit =
        if (event.op == PACKET_EXECUTION && sequence % numHandlers == index) {
            try {
                event.bb.putInt(NetlinkMessage.NLMSG_PID_OFFSET, pid)
                writer.write(event.bb)
                log.debug(s"Executed packet #$sequence")
            } catch { case t: Throwable =>
                log.error(s"Failed to execute packet #$sequence", t)
            }
        }
}
