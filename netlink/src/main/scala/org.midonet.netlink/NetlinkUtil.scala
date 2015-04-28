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

import org.midonet.netlink.rtnetlink.Rtnetlink

case class NetlinkHeader(len: Int, t: Short, flags: Short, seq: Int, pid: Int)

object NetlinkUtil {
    val NOTIFICATION_SEQ: Int = 0
    val DEFAULT_MAX_REQUESTS: Int  = 8
    val DEFAULT_MAX_REQUEST_SIZE: Int = 512
    val DEFAULT_RETRIES: Int = 10
    val DEFAULT_RTNETLINK_GROUPS: Int = Rtnetlink.Group.LINK.bitmask |
        Rtnetlink.Group.NOTIFY.bitmask |
        Rtnetlink.Group.NEIGH.bitmask |
        Rtnetlink.Group.TC.bitmask |
        Rtnetlink.Group.IPV4_IFADDR.bitmask |
        Rtnetlink.Group.IPV4_MROUTE.bitmask |
        Rtnetlink.Group.IPV4_ROUTE.bitmask |
        Rtnetlink.Group.IPV4_RULE.bitmask |
        Rtnetlink.Group.IPV6_IFADDR.bitmask |
        Rtnetlink.Group.IPV6_MROUTE.bitmask |
        Rtnetlink.Group.IPV6_ROUTE.bitmask |
        Rtnetlink.Group.IPV6_PREFIX.bitmask |
        Rtnetlink.Group.IPV6_RULE.bitmask
    // $ python -c "print hex(0b00000000000000000000000000010000)"
    // 0x10
    val DEFAULT_OVS_GROUPS: Int = 0x10
    val INITIAL_SEQ: Int = -1
    val NETLINK_READ_BUF_SIZE: Int = 0x10000

    val AlwaysTrueReader: Reader[Boolean] = new Reader[Boolean] {
        override def deserializeFrom(source: ByteBuffer) = true
    }

    def readNetlinkHeader(reply: ByteBuffer): NetlinkHeader = {
        val position: Int = reply.position

        val len: Int = reply.getInt()
        val nlType: Short = reply.getShort()
        val flags: Short = reply.getShort()
        val seq: Int = reply.getInt()
        val pid: Int = reply.getInt()

        val nextPosition: Int = position + len
        reply.limit(nextPosition)

        new NetlinkHeader(len, nlType, flags, seq, pid)
    }
}
