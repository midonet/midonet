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

package org.midonet.netlink

import java.nio.ByteBuffer

object NetlinkMessageWrapper {
    def apply(buf: ByteBuffer): NetlinkMessageWrapper = {
        buf.position(NetlinkMessage.HEADER_SIZE)
        new NetlinkMessageWrapper(buf)
    }

    def genl(buf: ByteBuffer, nlType: Short, command: Byte, version: Byte,
             flags: Short): NetlinkMessageWrapper = {
        buf.put(NetlinkMessage.GENL_CMD_OFFSET, command)
        buf.put(NetlinkMessage.GENL_VER_OFFSET, version)
        buf.putShort(NetlinkMessage.GENL_RESERVED_OFFSET, 0)
        buf.position(NetlinkMessage.GENL_HEADER_SIZE)
        new NetlinkMessageWrapper(buf)
            .withType(nlType)
            .withFlags(flags)
    }
}
/**
 * Value type representing a Netlink message. The buffer position points to
 * the beginning of a Netlink message. The caller must ensurer there is
 * enough space in the buffer. We assume the buffer contains only one
 * Netlink message starting at position 0.
 *
 * TODO: Move NetlinkMessage contents here and provide friendlier API when
 *       there are no Java callers.
 */
class NetlinkMessageWrapper(val buf: ByteBuffer) extends AnyVal {

    def withType(t: Short): NetlinkMessageWrapper = {
        buf.putShort(NetlinkMessage.NLMSG_TYPE_OFFSET, t)
        this
    }

    def withFlags(flags: Short): NetlinkMessageWrapper = {
        buf.putShort(NetlinkMessage.NLMSG_FLAGS_OFFSET, flags)
        this
    }

    def withoutFlags(): NetlinkMessageWrapper = withFlags(0)

    def withSeq(seq: Int): NetlinkMessageWrapper = {
        buf.putInt(NetlinkMessage.NLMSG_SEQ_OFFSET, seq)
        this
    }

    def finalize(pid: Int): Unit = {
        buf.putInt(NetlinkMessage.NLMSG_LEN_OFFSET, buf.position())
        buf.putInt(NetlinkMessage.NLMSG_PID_OFFSET, pid)
        buf.flip()
    }
}
