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
import java.nio.channels.SelectionKey

import com.typesafe.scalalogging.Logger
import rx.Observer

import org.midonet.netlink.exceptions.NetlinkException

object SelectorBasedNetlinkChannelReader {
    val SelectorTimeout = 0
}

/**
 * SelectorBasedNetlinkchannelReader provides the methods to start and stop a
 * thread to read Netlink replies from the kernel through NetlinkChannel.
 * You can pass different channels to the methods and start or stop read threads
 * for each channel.
 */
trait SelectorBasedNetlinkChannelReader {
    import SelectorBasedNetlinkChannelReader._

    protected val log: Logger
    val pid: Int
    val name = this.getClass.getName + pid

    private def startSelectorThread(channel: NetlinkChannel,
                                    threadName: String = name)
                                   (closure: SelectionKey => Unit): Unit = {
        val thread = new Thread(new Runnable {
            override def run(): Unit = try {
                val selector = channel.selector
                while (channel.isOpen) {
                    val readyChannel = selector.select(SelectorTimeout)
                    if (readyChannel > 0) {
                        val keys = selector.selectedKeys
                        val iter = keys.iterator()
                        while (iter.hasNext) {
                            val key: SelectionKey = iter.next()
                            closure(key)
                            iter.remove()
                        }
                    }
                }
            } catch {
                case ex: InterruptedException =>
                    log.error(s"$ex on netlink channel, STOPPTING", ex)
                    System.exit(1)
                case ex: Exception =>
                    log.error(s"$ex on netlink channel, ABORTING", ex)
                    System.exit(2)
            }
        })

        thread.start()
        thread.setName(threadName)
    }

    protected def startReadAndWriteThread(channel: NetlinkChannel,
                                          threadName: String = name)
                                         (readClosure: => Unit)
                                         (writeClosure: => Unit): Unit = {
        startSelectorThread(channel, threadName) { key: SelectionKey =>
            if (key.isWritable) {
                writeClosure
            }
            if (key.isReadable) {
                readClosure
            }
        }
        log.info("Starting netlink read and write thread: {}", threadName)
    }

    protected def startReadThread(channel: NetlinkChannel,
                                  threadName: String = name)
                                 (readClosure: => Unit): Unit = {
        startSelectorThread(channel, threadName) { key: SelectionKey =>
            if (key.isReadable) {
                readClosure
            }
        }
        log.info("Starting netlink read thread: {}", threadName)
    }

    protected def stopReadThread(channel: NetlinkChannel): Unit =
        channel.close()
}

/**
 * NetlinkNotificationReader provides the utilities for reading Netlink
 * notification messages from the kernel. The derived class MUST define
 * overridden notificationChannel as a lazy val because it's used in the
 * constructor.
 */
trait NetlinkNotificationReader {
    protected val log: Logger
    // notificationChannel is used right after the definition. So the users MUST
    // override notifiationChannel as a lazy val.
    protected val notificationChannel: NetlinkChannel
    if (notificationChannel.isBlocking) {
        notificationChannel.configureBlocking(false)
    }
    notificationChannel.register(
        notificationChannel.selector, SelectionKey.OP_READ)

    protected val notificationReadBuf =
        BytesUtil.instance.allocateDirect(NetlinkUtil.NETLINK_READ_BUF_SIZE)
    protected lazy val notificationReader: NetlinkReader =
        new NetlinkReader(notificationChannel)
    private lazy val headerSize: Int = notificationChannel.getProtocol match {
        case NetlinkProtocol.NETLINK_GENERIC =>
            NetlinkMessage.GENL_HEADER_SIZE
        case _ =>
            NetlinkMessage.HEADER_SIZE
    }

    protected
    def handleNotification(notificationObserver: Observer[ByteBuffer],
                           start: Int, size: Int): Unit = {
        val seq = notificationReadBuf.getInt(
            start + NetlinkMessage.NLMSG_SEQ_OFFSET)
        val `type` = notificationReadBuf.getShort(
            start + NetlinkMessage.NLMSG_TYPE_OFFSET)
        if (`type` >= NLMessageType.NLMSG_MIN_TYPE && size >= headerSize) {
            val oldLimit = notificationReadBuf.limit()
            notificationReadBuf.limit(start + size)
            notificationReadBuf.position(start)
            notificationObserver.onNext(notificationReadBuf)
            notificationReadBuf.limit(oldLimit)
        }
    }

    protected
    def readNotifications(notificationObserver: Observer[ByteBuffer]): Int =
        try {
            val nbytes = notificationReader.read(notificationReadBuf)
            notificationReadBuf.flip()
            var start = 0
            while (notificationReadBuf.remaining() >= headerSize) {
                val size = notificationReadBuf.getInt(
                    start + NetlinkMessage.NLMSG_LEN_OFFSET)
                handleNotification(notificationObserver, start, size)
                start += size
                notificationReadBuf.position(start)
            }
            nbytes
        } catch {
            case e: NetlinkException =>
                log.error(s"Error occurred during reading the notification: $e")
                notificationObserver.onError(e)
                0
        } finally {
            notificationReadBuf.clear()
        }
}
