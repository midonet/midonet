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

import scala.concurrent.duration._

/**
 * Utility class to write Netlink messages into a channel.
 */
class NetlinkWriter(val channel: NetlinkChannel) {

    /**
     * Writes from the source buffer into the the channel. Returns the amount
     * of bytes written. We assume the buffers contains one or more correctly
     * formatted Netlink messages.
     */
    @throws(classOf[IOException])
    def write(src: ByteBuffer): Int =
        channel.write(src)
}

class NetlinkBlockingWriter(channel: NetlinkChannel) extends NetlinkWriter(channel) {

    private val timeout = (100 millis).toMillis
    private val selector = channel.selector()
    private var seq = 0

    /**
     * Writes into the underlying channel, blocking regardless of the channel
     * mode while the write doesn't succeed.
     */
    @throws(classOf[IOException])
    override def write(src: ByteBuffer): Int = {
        var nbytes = 0
        do {
            nbytes = super.write(src)
        } while (nbytes == 0 && src.remaining() > 0 && waitForChannel())
        nbytes
    }

    private def waitForChannel(): Boolean =
        channel.isOpen && {
            channel.register(selector, SelectionKey.OP_WRITE)
            if (selector.select(timeout) > 0)
                selector.selectedKeys().clear()
            true
        }

    def writeRead[T](buf: ByteBuffer, f: ByteBuffer => T,
                     reader: NetlinkReader): T = {
        seq += 1

        buf.putInt(NetlinkMessage.NLMSG_SEQ_OFFSET, seq)
        write(buf)

        // read messages until we find the correct response
        buf.clear()
        reader.read(buf)
        var i = 0
        while (buf.getInt(i + NetlinkMessage.NLMSG_SEQ_OFFSET) != seq) {
            i += buf.getInt(i + NetlinkMessage.NLMSG_LEN_OFFSET)

            if (i >= buf.position) {
                buf.clear()
                reader.read(buf)
                i = 0
            }
        }

        buf.position(i + NetlinkMessage.GENL_HEADER_SIZE)
        buf.limit(i + buf.getInt(i + NetlinkMessage.NLMSG_LEN_OFFSET))
        val deserialized = f(buf)
        buf.clear()
        deserialized
    }
}
