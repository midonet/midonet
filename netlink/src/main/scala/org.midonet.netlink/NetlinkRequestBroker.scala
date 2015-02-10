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

import scala.concurrent.duration._

import rx.Observer

import org.midonet.netlink.exceptions.NetlinkException
import org.midonet.Util
import org.midonet.util.concurrent.NanoClock

object NetlinkRequestBroker {
    private val FREE = 0
    private val UPCALL = 0
    private val unsafe = Util.getUnsafe

    private sealed class RequestContext {

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

        /**
         * The sequence number currently active for this context. Because we
         * re-use NetlinkContext instances, we need this field to validate
         * whether we're getting a reply for a valid request or if it is the
         * delayed reply for a timed out request
         */
        var sequence: Int = _

        private val sequenceAddress = unsafe.objectFieldOffset(
                        classOf[RequestContext].getDeclaredField("sequence"))

        /**
         * Called by the thread handling replies. Protects this context
         * against being incorrectly expired by setting expiration to
         * Long.MaxValue.
         */
        def clear(): Unit = {
            expiration = Long.MaxValue
            observer = null
            unsafe.putOrderedInt(this, sequenceAddress, FREE)
        }

        /**
         * Prepare this context for a request.
         */
        def prepare(expiration: Long, observer: Observer[ByteBuffer],
                    sequence: Int): Unit = {
            this.expiration = expiration
            this.observer = observer
            unsafe.putOrderedInt(this, sequenceAddress, sequence)
        }
    }

    val NOOP = new Observer[ByteBuffer] {
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
 * Thread-safe for concurrent callers of request and readReply,
 * although the individual methods are not thread-safe.
 */
class NetlinkRequestBroker(reader: NetlinkReader,
                           writer: NetlinkBlockingWriter,
                           maxPendingRequests: Int,
                           readBuf: ByteBuffer,
                           clock: NanoClock,
                           timeout: Duration = 1 second) {
    import NetlinkRequestBroker._

    val capacity = Util.findNextPositivePowerOfTwo(maxPendingRequests)
    private val mask = capacity - 1
    private var sequenceNumber = 0
    private val timeoutNanos = timeout.toNanos
    private val requests = new Array[RequestContext](capacity)

    {
        var i = 0
        while (i < capacity) {
            requests(i) = new RequestContext
            requests(i).clear()
            i += 1
        }
    }

    /**
     * Makes a request, consisting of a serialized Netlink message, and
     * registers an Observer through which the reply will be streamed.
     * Returns the number of bytes written.
     */
    def writeRequest(src: ByteBuffer, observer: Observer[ByteBuffer]): Int = {
        val ctx = acquireContext(clock.tick + timeoutNanos, observer)
        try {
            if (ctx ne null) {
                src.putInt(src.position() + NetlinkMessage.NLMSG_SEQ_OFFSET,
                           ctx.sequence)
                writer.write(src)
            } else {
                0
            }
        } catch { case t: Throwable =>
            if (ctx ne null) {
                ctx.clear()
            }
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
    def readReply(unhandled: Observer[ByteBuffer] = NOOP): Int =
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
            val seq = readBuf.getInt(NetlinkMessage.NLMSG_SEQ_OFFSET)
            val ctx = getContext(seq)
            val obs = getObserver(seq, ctx, unhandled)
            ctx.clear()
            obs.onError(e)
            0
        } finally {
            readBuf.clear()
            doTimeoutExpiration()
        }

    private def handleReply(reply: ByteBuffer, unhandled: Observer[ByteBuffer],
                            start: Int, size: Int): Unit = {
        val seq = readBuf.getInt(start + NetlinkMessage.NLMSG_SEQ_OFFSET)
        val ctx = getContext(seq)
        val observer = getObserver(seq, ctx, unhandled)

        val `type` = readBuf.getShort(start + NetlinkMessage.NLMSG_TYPE_OFFSET)
        if (`type` >= NLMessageType.NLMSG_MIN_TYPE &&
            size >= NetlinkMessage.GENL_HEADER_SIZE) {

            val flags = readBuf.getShort(start + NetlinkMessage.NLMSG_FLAGS_OFFSET)

            val oldLimit = readBuf.limit()
            readBuf.limit(start + size)
            readBuf.position(start + NetlinkMessage.GENL_HEADER_SIZE)
            observer.onNext(readBuf)
            readBuf.limit(oldLimit)

            if (NLFlag.isMultiFlagSet(flags)) { // if (NLFlag.isMultiFlagSet(flags) && `type` != NLMessageType.DONE) {
                return
            }
        }

        ctx.clear()
        observer.onCompleted()
    }

    private def getContext(seq: Int) = requests(seq & mask)

    private def getObserver(seq: Int, ctx: RequestContext,
                            orElse: Observer[ByteBuffer]) =
        if (seq != UPCALL && ctx.sequence == seq) {
            ctx.observer
        } else {
            orElse
        }

    private def nextSequenceNumber(): Int = {
        var seq = sequenceNumber + 1
        if (seq == UPCALL) {
            seq = UPCALL + 1
        }
        sequenceNumber = seq
        seq
    }

    private def acquireContext(expiration: Long,
                               observer: Observer[ByteBuffer]): RequestContext = {
        var i = 0
        do {
            val seq = nextSequenceNumber()
            val ctx = requests(seq & mask)
            if (ctx.sequence == FREE) {
                ctx.prepare(expiration, observer, seq)
                return ctx
            }
            i += 1
        } while (i < capacity)
        null
    }

    private def doTimeoutExpiration(): Unit = {
        val currentTime = clock.tick
        var i = 0
        while (i < capacity) {
            val ctx = requests(i)
            if (ctx.sequence != FREE && currentTime > ctx.expiration) {
                val obs = ctx.observer
                ctx.clear()
                obs.onError(timeoutException)
            }
            i += 1
        }
    }
}
