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
import java.nio.channels.{AsynchronousCloseException, ClosedByInterruptException}
import java.util.concurrent.atomic.AtomicInteger

import rx.Observer

import org.midonet.netlink.rtnetlink.Rtnetlink

case class NetlinkHeader(len: Int, t: Short, flags: Short, seq: Int, pid: Int)

object NetlinkUtil {
    val DEFAULT_MAX_REQUESTS: Int  = 8
    val DEFAULT_MAX_REQUEST_SIZE: Int = 512
    val DEFAULT_RETRIES: Int = 10
    val NO_NOTIFICATION: Int = 0
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
    val NETLINK_READ_BUF_SIZE: Int = 0x01000  // Defaults to the page size, 4k

    val AlwaysTrueReader: Reader[Boolean] = new Reader[Boolean] {
        override def deserializeFrom(source: ByteBuffer) = true
    }

    /**
     * Repeatedly reads the netlink notifications from the kernel.
     *
     * @param notificationChannel the Netlink channel to read
     * @param notificationReader the reader of the netlink channel
     * @param headerSize the size of the Netlink header
     * @param notificationObserver the Observer which onNext is callled with the
     *                             populated notificatonReadBuf
     * @throws java.nio.channels.ClosedByInterruptException
     * @throws java.nio.channels.AsynchronousCloseException
     */
    @throws(classOf[ClosedByInterruptException])
    @throws(classOf[AsynchronousCloseException])
    def readNetlinkNotifications(notificationChannel: NetlinkChannel,
                                 notificationReader: NetlinkReader,
                                 headerSize: Int,
                                 notificationObserver: Observer[ByteBuffer]) =
        while (notificationChannel.isOpen) {
            val buf =
                BytesUtil.instance.allocate(NETLINK_READ_BUF_SIZE)
            val nbytes = notificationReader.read(buf)
            if (nbytes > 0) {
                buf.flip()
                val nlType = buf.getShort(NetlinkMessage.NLMSG_TYPE_OFFSET)
                val size = buf.getInt(
                    NetlinkMessage.NLMSG_LEN_OFFSET)
                if (nlType >= NLMessageType.NLMSG_MIN_TYPE &&
                    size >= headerSize) {
                    buf.limit(size)
                    notificationObserver.onNext(buf)
                }
            }
        }

    var sequenceGenerator = new AtomicInteger(0)
    def rpc[T](
            buf: ByteBuffer,
            writer: NetlinkWriter,
            reader: NetlinkReader,
            f: ByteBuffer => T,
            headerSize: Int = NetlinkMessage.GENL_HEADER_SIZE): T =
        try {
            val seq = sequenceGenerator.incrementAndGet()
            buf.putInt(NetlinkMessage.NLMSG_SEQ_OFFSET, seq)
            writer.write(buf)
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

            buf.position(i + headerSize)
            buf.limit(i + buf.getInt(i + NetlinkMessage.NLMSG_LEN_OFFSET))
            f(buf)
        } finally {
            buf.clear()
        }
}
