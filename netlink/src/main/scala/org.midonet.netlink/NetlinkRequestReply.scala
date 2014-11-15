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
import java.util.concurrent.atomic.AtomicInteger

import scala.concurrent.duration._

import rx.Observer

import org.midonet.netlink.exceptions.NetlinkException
import org.midonet.util.concurrent.NanoClock

object NetlinkRequestReply {
    private val EMPTY = -1

    private sealed class NetlinkContext {

        /**
         * The time when the underlying request expires.
         */
        var expiration: Long = _

        /**
         * onNext is called with the Netlink reply payload (which can be part
         * of a multi); onError is called when the Netlink reply is an error;
         * onComplete is called on an ACK or at the end of all the answers to
         * the request.
         */
        var observer: Observer[ByteBuffer] = _

        def clear(): Unit = {
            expiration = Long.MaxValue
            observer = null
        }
    }

    private val NOOP = new Observer[ByteBuffer] {
        override def onCompleted(): Unit = { }
        override def onError(e: Throwable): Unit = { }
        override def onNext(t: ByteBuffer): Unit = { }
    }

    private val timeoutException = new NetlinkException(
        NetlinkException.ErrorCode.ETIMEOUT,
        "Timeout while waiting for Nelink replies") {

        override def fillInStackTrace() = this
    }
}

/**
 * Class through which to make Netlink requests that require a reply.
 * Thread-safe for concurrent callers of request and processReplies,
 * but the individual methods are only thread-safe if the registered
 * observers and the underlying reader and writer are also thread-safe.
 */
class NetlinkRequestReply(reader: NetlinkReader,
                          writer: NetlinkBlockingWriter,
                          val maxPendingRequests: Int,
                          readBuf: ByteBuffer,
                          clock: NanoClock,
                          timeout: Duration = 100 millis) {
    import org.midonet.netlink.NetlinkRequestReply._

    private val timeoutNanos = timeout.toNanos
    // Sequence number 0 is reserved for upcall packets
    private val pending = new Array[NetlinkContext](maxPendingRequests + 1)

    {
        var i = 0
        while (i < pending.length) {
            pending(i) = new NetlinkContext
            pending(i).clear()
            i += 1
        }
    }

    private val freeList = new Array[Int](pending.length)

    {
        var i = 2
        while (i < freeList.length) {
            freeList(i - 1) = i
            i += 1
        }
        freeList(i - 1) = EMPTY
    }

    private val freeIndex = new AtomicInteger(1)

    /**
     * Makes a request, consisting of a serialized Netlink message, and
     * registers an Observer through which the reply will be streamed.
     * Returns the number of bytes written.
     */
    def request(src: ByteBuffer, observer: Observer[ByteBuffer]): Int = {
        val seq = acquireSeq()
        try {
            if (seq == EMPTY) {
                0
            } else {
                src.putInt(src.position() + NetlinkMessage.NLMSG_SEQ_OFFSET, seq)
                val ctx = pending(seq)
                ctx.expiration = clock.tick + timeoutNanos
                ctx.observer = observer
                writer.write(src)
            }
        } catch { case t: Throwable =>
            releaseSeq(seq)
            observer.onError(t)
            0
        }
    }

    /**
     * Processes a reply - a stream of ByteBuffers - if one is available.
     * Any reply that doesn't match a valid sequence number is passed on to
     * the optional unhandled Observer. Returns the number of bytes read.
     */
    @throws(classOf[IOException])
    def processReply(unhandled: Observer[ByteBuffer] = NOOP): Int =
        try {
            val nbytes = reader.read(readBuf)
            var start = 0
            while (readBuf.remaining() >= NetlinkMessage.HEADER_SIZE) {
                val size = readBuf.getInt(start + NetlinkMessage.NLMSG_LEN_OFFSET)
                handleReply(readBuf, unhandled, start, size)
                start += size
                readBuf.position(start)
            }
            nbytes
        } catch { case e: NetlinkException =>
            getObserver(readBuf.getInt(NetlinkMessage.NLMSG_SEQ_OFFSET), unhandled).onError(e)
            0
        } finally {
            readBuf.clear()
            doTimeoutExpiration()
        }

    private def handleReply(reply: ByteBuffer, unhandled: Observer[ByteBuffer],
                            start: Int, size: Int): Unit = {
        val seq = readBuf.getInt(start + NetlinkMessage.NLMSG_SEQ_OFFSET)
        val observer = getObserver(seq, unhandled)

        val `type` = readBuf.getShort(start + NetlinkMessage.NLMSG_TYPE_OFFSET)
        if (`type` != NLMessageType.DONE && `type` != NLMessageType.NOOP &&
            size >= NetlinkMessage.GENL_HEADER_SIZE) {

            val flags = readBuf.getShort(start + NetlinkMessage.NLMSG_FLAGS_OFFSET)

            val oldLimit = readBuf.limit()
            readBuf.limit(start + size)
            readBuf.position(start + NetlinkMessage.GENL_HEADER_SIZE)
            observer.onNext(readBuf)
            readBuf.limit(oldLimit)

            if (NLFlag.isMultiFlagSet(flags)) {
                return
            }
        }

        releaseSeq(seq)
        observer.onCompleted()
    }

    private def getObserver(seq: Int, orElse: Observer[ByteBuffer]) =
        if (seq < 1 || seq > pending.length) {
            orElse
        } else {
            val obs = pending(seq).observer
            if (obs ne null) {
                obs
            } else {
                orElse
            }
        }

    private def acquireSeq(): Int = {
        var curFree = 0
        var nextFree = 0
        do {
            curFree = freeIndex.get()
            if (curFree == EMPTY) {
                return EMPTY
            }
            nextFree = freeList(curFree)
        } while (!freeIndex.compareAndSet(curFree, nextFree))
        curFree
    }

    private def releaseSeq(seq: Int): Unit = {
        var curFree = 0
        pending(seq).clear()
        do {
            curFree = freeIndex.get()
            freeList(seq) = curFree
        } while (!freeIndex.compareAndSet(curFree, seq))
    }

    private def doTimeoutExpiration(): Unit = {
        val currentTime = clock.tick
        var i = 0
        while (i < pending.length) {
            val req = pending(i)
            if (currentTime >= req.expiration) {
                val obs = pending(i).observer
                releaseSeq(i)
                obs.onError(timeoutException)
            }
            i += 1
        }
    }
}
