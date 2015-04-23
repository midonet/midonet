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

import com.typesafe.scalalogging.Logger
import rx.Observer

/**
 * NetlinkNotificationReader provides the utilities for reading Netlink
 * notification messages from the kernel. The derived class MUST define
 * overridden notificationChannel as a lazy val because it's used in the
 * constructor.
 */
trait NetlinkNotificationReader {
    protected val log: Logger
    protected val pid: Int
    protected val name: String
    protected val notificationChannel: NetlinkChannel
    protected val notificationObserver: Observer[ByteBuffer]
    protected val netlinkReadBufSize = NetlinkUtil.NETLINK_READ_BUF_SIZE

    protected val notificationReadBuf =
        BytesUtil.instance.allocateDirect(netlinkReadBufSize)
    protected lazy val notificationReader: NetlinkReader =
        new NetlinkReader(notificationChannel)
    private lazy val headerSize: Int = notificationChannel.getProtocol match {
        case NetlinkProtocol.NETLINK_GENERIC =>
            NetlinkMessage.GENL_HEADER_SIZE
        case _ =>
            NetlinkMessage.HEADER_SIZE
    }

    protected val notificationReadThread = new Thread(s"$name-notification") {
        override def run(): Unit = try {
            while (notificationChannel.isOpen) {
                val nbytes = notificationReader.read(notificationReadBuf)
                notificationReadBuf.flip()
                if (notificationReadBuf.remaining() >= headerSize) {
                    val nlType = notificationReadBuf.getShort(
                        NetlinkMessage.NLMSG_TYPE_OFFSET)
                    val size = notificationReadBuf.getInt(
                        NetlinkMessage.NLMSG_LEN_OFFSET)
                    if (nlType >= NLMessageType.NLMSG_MIN_TYPE &&
                        size >= headerSize) {
                        val oldLimit = notificationReadBuf.limit()
                        notificationReadBuf.limit(size)
                        notificationReadBuf.position(0)
                        notificationObserver.onNext(notificationReadBuf)
                        notificationReadBuf.limit(oldLimit)
                    }
                }
                notificationReadBuf.clear()
            }
        } catch {
            case ex @ (_: InterruptedException |
                       _: ClosedByInterruptException|
                       _: AsynchronousCloseException) =>
                log.info(s"$ex on rtnetlink notification channel, STOPPING")
            case ex: Exception =>
                log.error(s"$ex on rtnetlink notification channel, ABORTING",
                    ex)
        }
    }
}
