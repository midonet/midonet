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

import org.midonet.netlink.clib.cLibrary
import org.midonet.netlink.exceptions.NetlinkException

object NetlinkReader {
    object MessageTruncated extends NetlinkException(NetlinkException.GENERIC_IO_ERROR,
                                                     "Message truncated") {
        override def fillInStackTrace() = this
    }
}

/**
 * Utility class to read Netlink messages off a channel.
 */
class NetlinkReader(val channel: NetlinkChannel) {

    import NetlinkReader._

    /**
     * Reads from the channel into the destination buffer. Netlink errors are
     * communicated by throwing a NetlinkException. This method will not modify
     * the buffer's position. The amount of bytes read is returned and it
     * matches dst.remaining().
     */
    @throws(classOf[IOException])
    @throws(classOf[NetlinkException])
    def read(dst: ByteBuffer): Int = {
        val start = dst.position()
        val nbytes = try {
            channel.read(dst)
        } finally {
            dst.position(start)
        }

        dst.limit(nbytes)

        if (nbytes >= NetlinkMessage.HEADER_SIZE) {
            val msgType = dst.getShort(start + NetlinkMessage.NLMSG_TYPE_OFFSET)

            if (msgType == NLMessageType.ERROR) {
                val error = dst.getInt(start + NetlinkMessage.NLMSG_ERROR_OFFSET)
                if (error != 0) {
                    val errorMessage = cLibrary.lib.strerror(-error)
                    throw new NetlinkException(-error, errorMessage)
                }
            } else if (NetlinkMessage.isTruncated(dst) ||
                       msgType == NLMessageType.OVERRUN) {
                throw MessageTruncated
            }
        } else if (nbytes > 0) {
            throw MessageTruncated
        }
        nbytes
    }
}
