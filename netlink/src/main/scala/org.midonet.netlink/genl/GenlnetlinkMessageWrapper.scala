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

package org.midonet.netlink.genl

import java.nio.ByteBuffer

import org.midonet.netlink.{NetlinkMessageWrapper, NetlinkMessage, NetlinkRequestContext}

object GenlnetlinkMessageWrapper {
    def apply(buf: ByteBuffer): GenlnetlinkMessageWrapper = {
        buf.position(NetlinkMessage.GENL_HEADER_SIZE)
        new GenlnetlinkMessageWrapper(buf)
    }
}
/**
 * Value type representing a Generic Netlink message. The buffer position points
 * to the beginning of a Netlink message. The caller must ensurer there is
 * enough space in the buffer. We assume the buffer contains only one Generic
 * Netlink message starting at position 0.
 */
class GenlnetlinkMessageWrapper(val buf: ByteBuffer) extends AnyVal {
    def withContext(ctx: NetlinkRequestContext): GenlnetlinkMessageWrapper = {
        buf.putShort(NetlinkMessage.NLMSG_TYPE_OFFSET, ctx.commandFamily)
        buf.put(NetlinkMessage.GENL_CMD_OFFSET, ctx.command)
        buf.put(NetlinkMessage.GENL_VER_OFFSET, ctx.version)
        this
    }

/*    override def withType(t: Short): GenlnetlinkMessageWrapper = {
        super.withType(t)
        this
    }

    override def withFlags(flags: Short): GenlnetlinkMessageWrapper = {
        super.withFlags(flags)
        this
    }

    override def withoutFlags(): GenlnetlinkMessageWrapper = withFlags(0)

    override def withSeq(seq: Int): GenlnetlinkMessageWrapper = {
        super.withSeq(seq)
        this
    }*/

    def finalize(pid: Int): Unit = {
        buf.putInt(NetlinkMessage.NLMSG_LEN_OFFSET, buf.position())
        buf.putInt(NetlinkMessage.NLMSG_PID_OFFSET, pid)
        buf.putShort(NetlinkMessage.GENL_RESERVED_OFFSET, 0)
        buf.flip()
    }

    def toGeneric: GenlnetlinkMessageWrapper = this

    def toNetlink: NetlinkMessageWrapper = new NetlinkMessageWrapper(buf)
}
