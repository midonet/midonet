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

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.SelectionKey
import java.util.concurrent.ExecutionException

import scala.collection.JavaConversions._

import rx.Observer

import org.midonet.netlink._
import org.midonet.util.concurrent.NanoClock

object SelectorBasedRtnetlinkConnection extends
        RtnetlinkConnectionFactory[SelectorBasedRtnetlinkConnection] {
    private val SelectorTimeout = 0

    override def apply() = {
        val conn = super.apply()
        conn.start()
        conn
    }
}

class SelectorBasedRtnetlinkConnection(channel: NetlinkChannel,
                                       maxPendingRequests: Int,
                                       maxRequestSize: Int,
                                       clock: NanoClock)
        extends RtnetlinkConnection(channel, maxPendingRequests,
            maxRequestSize, clock) {
    import org.midonet.netlink.rtnetlink.SelectorBasedRtnetlinkConnection._

    val name = this.getClass.getName + pid

    log.info("Starting rtnetlink connection {}", name)
    channel.configureBlocking(false)
    channel.register(channel.selector,
        SelectionKey.OP_READ | SelectionKey.OP_WRITE)

    private def stopReadThread(): Unit = channel.close()

    protected def readMessage(observer: Observer[ByteBuffer] =
                              notificationObserver): Unit =
        if (channel.isOpen) {
            if (observer != null) {
                requestBroker.readReply(observer)
            } else {
                requestBroker.readReply()
            }

        }

    private def startReadThread(threadName: String = name): Unit = {
        val thread = new Thread(new Runnable {
            override def run(): Unit = try {
                val currentThread = Thread.currentThread()
                val selector = channel.selector
                while (channel.isOpen) {
                    val readyChannel = selector.select(SelectorTimeout)
                    if (readyChannel != 0) {
                        val keys = selector.selectedKeys()
                        val iter: Iterator[SelectionKey] = keys.iterator()
                        while (iter.hasNext) {
                            val key: SelectionKey = iter.next()
                            if (key.isReadable) {
                                readMessage()
                            }
                            iter.remove()
                        }
                    }
                }
            } catch {
                case ex: InterruptedException =>
                    log.error("{}: {} on netlink channl, SOPPTING {}",
                        name, ex.getClass.getName, ex)
                    System.exit(1)
                case ex: IOException =>
                    log.error("{}: {} on netlink channl, ABORTING {}",
                        name, ex.getClass.getName, ex)
                    System.exit(2)
            }
        })

        log.info("Starting rtnetlink read thread: {}", threadName)
        thread.start()
        thread.setName(threadName)
    }

    @throws[IOException]
    @throws[InterruptedException]
    @throws[ExecutionException]
    def start(): Unit = try {
        startReadThread()
    } catch {
        case ex: IOException => try {
            stop()
        } catch {
            case _: Exception => throw ex
        }
    }

    def stop(): Unit = {
        log.info("Stopping rtnetlink connection: {}", name)
        stopReadThread()
        notificationObserver.onCompleted()
    }
}