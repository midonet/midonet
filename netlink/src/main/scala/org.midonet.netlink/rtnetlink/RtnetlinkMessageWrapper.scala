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

package org.midonet.netlink.rtnetlink

import java.nio.ByteBuffer

import org.midonet.netlink.{NetlinkMessageWrapper, NetlinkMessage}

object RtnetlinkMessageWrapper {
    def apply(buf: ByteBuffer): RtnetlinkMessageWrapper = {
        buf.position(NetlinkMessage.HEADER_SIZE)
        new RtnetlinkMessageWrapper(buf)
    }
}

class RtnetlinkMessageWrapper private (override val buf: ByteBuffer)
        extends NetlinkMessageWrapper(buf) {

    def withRtaLen(len: Short): RtnetlinkMessageWrapper = {
        buf.putShort(NetlinkMessage.RTA_LEN_OFFSET, len)
        this
    }

    def withRtaType(t: Short): RtnetlinkMessageWrapper = {
        buf.putShort(NetlinkMessage.RTA_TYPE_OFFSET, t)
        this
    }

    override def finalize(pid: Int): Unit = {
        buf.putInt(NetlinkMessage.NLMSG_LEN_OFFSET, buf.position)
        buf.putInt(NetlinkMessage.NLMSG_PID_OFFSET, pid)
        buf.flip()
    }
}
