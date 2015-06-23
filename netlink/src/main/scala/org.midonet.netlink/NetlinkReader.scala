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

package org.midonet.netlink

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.SelectionKey
import java.util.concurrent.TimeoutException

import scala.concurrent.duration.Duration

import org.midonet.util.cLibrary
import org.midonet.netlink.exceptions.NetlinkException

object NetlinkReader {
    object MessageTruncated extends NetlinkException(NetlinkException.GENERIC_IO_ERROR,
                                                     "Message truncated") {
        override def fillInStackTrace() = this
    }

    private def isTruncated(dst: ByteBuffer, nbytes: Int, start: Int) =
        nbytes < dst.getInt(start + NetlinkMessage.NLMSG_LEN_OFFSET)
}

/**
 * Utility class to read Netlink messages off a channel.
 */
class NetlinkReader(channel: NetlinkChannel) {

    import NetlinkReader._

    /**
     * Reads from the channel into the destination buffer. Netlink errors are
     * communicated by throwing a NetlinkException. Returns the amount of
     * bytes read.
     */
    @throws(classOf[IOException])
    @throws(classOf[NetlinkException])
    def read(dst: ByteBuffer): Int = {
        val start = dst.position()
        val nbytes = channel.read(dst)

        if (nbytes >= NetlinkMessage.HEADER_SIZE) {
            val msgType = dst.getShort(start + NetlinkMessage.NLMSG_TYPE_OFFSET)

            if (msgType == NLMessageType.ERROR) {
                val error = dst.getInt(start + NetlinkMessage.NLMSG_ERROR_OFFSET)
                if (error != 0) {
                    val errorMessage = cLibrary.lib.strerror(-error)
                    throw new NetlinkException(-error, errorMessage)
                }
            } else if (isTruncated(dst, nbytes, start) || msgType == NLMessageType.OVERRUN) {
                throw MessageTruncated
            }
        } else if (nbytes > 0) {
            throw MessageTruncated
        }
        nbytes
    }
}

class NetlinkTimeoutReader(
        channel: NetlinkChannel,
        timeout: Duration) extends NetlinkReader(channel) {
    private val timeoutMillis = timeout.toMillis
    private val selector = channel.selector()
    private val selectionKey = {
        if (channel.isBlocking)
            channel.configureBlocking(false)
        channel.register(selector, SelectionKey.OP_READ)
    }

    @throws(classOf[IOException])
    @throws(classOf[NetlinkException])
    @throws(classOf[TimeoutException])
    override def read(dst: ByteBuffer): Int = {
        selector.select(timeoutMillis)
        if (selectionKey.isReadable) {
            super.read(dst)
        } else {
            throw new TimeoutException()
        }
    }
}
