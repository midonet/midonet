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
import java.util.concurrent.atomic.{AtomicIntegerArray, AtomicLong}

import scala.concurrent.duration._

import rx.Observer

import org.midonet.netlink.exceptions.NetlinkException
import org.midonet.Util
import org.midonet.util.concurrent.NanoClock

object NetlinkRequestBroker {
    val FULL = -1
    private val FREE = -1
    private val AVAILABLE = 0
    private val REPLIED = 1

    private val NOOP = new Observer[ByteBuffer] {
        override def onCompleted(): Unit = { }
        override def onError(e: Throwable): Unit = { }
        override def onNext(t: ByteBuffer): Unit = { }
    }

    private object timeoutException extends NetlinkException(
        NetlinkException.ErrorCode.ETIMEOUT,
        "Timeout while waiting for Nelink replies") {

        override def fillInStackTrace() = this
    }
}

/**
 * Class through which to make Netlink requests that require a reply. Do not
 * use this class to receive kernel notifications. It expects one or more
 * publisher threads to call the nextSequence(), get() and publishRequest()
 * methods, a single writer thread to call writePublishedRequests() and a single
 * reader thread to call handleReply().
 *
 * Synchronization:
 *
 * Publisher threads synchronize amongst themselves by doing a CAS on the
 * sequence field. They synchronize with the writer and reader threads through
 * the respective sequence fields (writtenSequence and readSequence). Note that
 * the read thread can advance faster than the write thread: after writing the
 * request, the writer still has to clear the buffer used to carry the request.
 * After claiming a sequence, a publisher thread can obtain a ByteBuffer wherein
 * to serialize the netlink request. It then publishes the request for writing
 * by marking the corresponding position of the publishedSequences array as
 * AVAILABLE.
 *
 * The writer thread, starting at writtenSequence, writes all the subsequent
 * requests that have been published. After it's done, it updates that sequence
 * so that waiting publisher threads can progress.
 *
 * The reader thread reads the replies from the kernel. We optimize for the case
 * where the requests are received in the order they are written. When a reply
 * is received, we mark the corresponding position in the publishedSequences as
 * REPLIED. A publisher thread trying to claim a sequence has thus to wait for
 * the reply to the oldest request. At the end of handleReply() we advance the
 * readSequence by skipping over the continuous published requests that are
 * either REPLIED or timed out.
 *
 * Timeouts:
 *
 * We support timing out a pending request. Note that this is not built into the
 * protocol nor is it supported by libraries such as libnl. They should be used
 * mostly to avoid stalling publisher threads when a request is not made but
 * no error is detected. We don't support timing out a request if we'll eventually
 * receive the reply: if the request is in position X and the reply arrives after
 * we have wrapped around to X again, it may be mistaken as the new reply.
 *
 *
 * TODO: Use @Contended on some of these fields when on java 8
 */
final class NetlinkRequestBroker(writer: NetlinkBlockingWriter,
                                 reader: NetlinkReader,
                                 maxPendingRequests: Int,
                                 maxRequestSize: Int,
                                 readBuf: ByteBuffer,
                                 clock: NanoClock,
                                 timeout: Duration = 10 seconds) {
    import NetlinkRequestBroker._

    val capacity = Util.findNextPositivePowerOfTwo(maxPendingRequests)
    private val mask = capacity - 1

    /**
     * The pre-allocated buffer. Each request is assigned a slice from this buffer.
     */
    private val buffer = BytesUtil.instance.allocateDirect(capacity * maxRequestSize)
    private val buffers = new Array[ByteBuffer](capacity)

    {
        var i = 0
        while (i < capacity) {
            buffer.limit(buffer.position() + maxRequestSize)
            buffers(i) = buffer.slice().order(buffer.order())
            buffer.position(buffer.limit())
            i += 1
        }
    }

    /**
     * The highest published sequence for writing. Used to synchronize between
     * the producer thread and the write thread.
     */
    private val sequence = new AtomicLong(-1L)

    /**
     * Cached value of Min(writtenSequence, readSequence) to avoid volatile reads.
     */
    private var cachedWriterReaderSequence = -1L

    /**
     * The highest written sequence. Confined to the writer thread.
     */
    @volatile private var writtenSequence = -1L

    /**
     * The highest read sequence. Confined to the reader thread.
     */
    @volatile private var readSequence = -1L

    private val publishedSequences = new AtomicIntegerArray(capacity)

    {
        var i = 0
        while (i < capacity) {
            publishedSequences.set(i, FREE)
            i += 1
        }
    }

    /**
     * The observers registered by the producer thread, through which the
     * reader thread will feed the replies.
     */
    private val observers = new Array[Observer[ByteBuffer]](capacity)

    /**
     * The expiration deadlines set by the producer thread and processed by
     * the reader thread.
     */
    private val expirations = Array.fill(capacity)(Long.MaxValue)
    private val timeoutNanos = timeout.toNanos

    def hasRequestsToWrite: Boolean =
        isAvailable(position(writtenSequence + 1))

    /**
     * Gets the next sequence available for publishing a request.
     */
    def nextSequence(): Long = {
        var seq = 0L
        var next = 0L
        do {
            seq = sequence.get()
            next = seq + 1

            if (!hasAvailableCapacity(seq)) {
                return FULL
            }
        } while (!sequence.compareAndSet(seq, next))
        next
    }

    private def hasAvailableCapacity(seq: Long): Boolean =
        if (seq - cachedWriterReaderSequence > capacity) {
            val minSequence = Math.min(writtenSequence, readSequence)
            cachedWriterReaderSequence = minSequence
            seq - minSequence < capacity
        } else true

    /**
     * Returns the ByteBuffer corresponding to the specified sequence number.
     * The caller must serialize a valid Netlink request into this buffer. Note
     * that the sequence number will be written by the writer, so it need not
     * be filled by the caller.
     */
    def get(seq: Long): ByteBuffer =
        buffers(position(seq))

    /**
     * Publishes a Netlink request, registering an Observer through which the
     * reply will be streamed. Synchronizes with the writer thread via the
     * publishedSequences array and, transitively, with the reader thread.
     */
    def publishRequest(seq: Long, observer: Observer[ByteBuffer]): Unit = {
        val pos = position(seq)
        observers(pos) = observer
        publishedSequences.lazySet(pos, AVAILABLE)
    }

    /**
     * Writes all the new published requests. Returns the number of
     * bytes written.
     */
    def writePublishedRequests(): Int = {
        var seq = writtenSequence
        var nbytes = 0
        var pos = 0
        while ({ pos = position(seq + 1); isAvailable(pos) }) {
            val buf = buffers(pos)
            try {
                buf.putInt(buf.position() + NetlinkMessage.NLMSG_SEQ_OFFSET, pos)
                nbytes += writer.write(buf)
                expirations(pos) = clock.tick + timeoutNanos
            } catch { case e: Throwable =>
                observers(pos).onError(e)
                publishedSequences.lazySet(pos, REPLIED)
            } finally {
                // IOUtil modifies the buffer's position after the write has
                // been performed, so this method is the best place to clear it.
                buf.clear()
            }
            seq += 1
        }
        writtenSequence = seq
        nbytes
    }


    /**
     * Processes a reply - a stream of ByteBuffers - if one is available.
     * Any reply that doesn't match a valid sequence number is passed on to
     * the optional unhandled Observer. Returns the number of bytes read.
     */
    @throws(classOf[IOException])
    def readReply(unhandled: Observer[ByteBuffer] = NOOP): Int = {
        try {
            val nbytes = reader.read(readBuf)
            readBuf.flip()
            var start = 0
            while (readBuf.remaining() >= NetlinkMessage.HEADER_SIZE) {
                val size = readBuf.getInt(start + NetlinkMessage.NLMSG_LEN_OFFSET)
                handleReply(readBuf, unhandled, start, size)
                start += size
                readBuf.position(start)
            }
            advanceReadSeqAndCheckTimeouts()
            nbytes
        } catch { case e: NetlinkException =>
            val pos = readBuf.getInt(NetlinkMessage.NLMSG_SEQ_OFFSET)
            getObserver(pos, unhandled).onError(e)
            publishedSequences.lazySet(pos, REPLIED)
            0
        } finally {
            readBuf.clear()
        }
    }

    private def handleReply(reply: ByteBuffer, unhandled: Observer[ByteBuffer],
                            start: Int, size: Int): Unit = {
        val pos = readBuf.getInt(start + NetlinkMessage.NLMSG_SEQ_OFFSET)
        val obs = getObserver(pos, unhandled)

        val `type` = readBuf.getShort(start + NetlinkMessage.NLMSG_TYPE_OFFSET)
        if (`type` >= NLMessageType.NLMSG_MIN_TYPE &&
            size >= NetlinkMessage.GENL_HEADER_SIZE) {

            val flags = readBuf.getShort(start + NetlinkMessage.NLMSG_FLAGS_OFFSET)

            val oldLimit = readBuf.limit()
            readBuf.limit(start + size)
            readBuf.position(start + NetlinkMessage.GENL_HEADER_SIZE)
            obs.onNext(readBuf)
            readBuf.limit(oldLimit)

            if (NLFlag.isMultiFlagSet(flags)) {
                return
            }
        }

        publishedSequences.lazySet(pos, REPLIED)
        obs.onCompleted()
    }

    /**
     * Returns the registered observer if we were expecting this reply or
     * unhandled if this the reply for a request that has already timed out.
     */
    private def getObserver(pos: Int,
                            unhandled: Observer[ByteBuffer]): Observer[ByteBuffer] =
        if (publishedSequences.get(pos) == AVAILABLE)
            observers(pos)
        else
            unhandled

    private def advanceReadSeqAndCheckTimeouts(): Unit = {
        val currentTime = clock.tick
        var seq = readSequence
        var pos = 0
        while ({pos = position(seq + 1)
                isReplied(pos) || timedOut(pos, currentTime)}) {
            freeObserver(pos)
            seq += 1
        }
        readSequence = seq
    }

    private def timedOut(seq: Long, currentTime: Long): Boolean = {
        val pos = position(seq)
        if (isAvailable(pos) && currentTime > expirations(pos)) {
            observers(pos).onError(timeoutException)
            true
        } else {
            false
        }
    }

    private def freeObserver(pos: Int): Unit = {
        observers(pos) = null
        expirations(pos) = Long.MaxValue
        publishedSequences.lazySet(pos, FREE)
    }

    private def isReplied(pos: Int): Boolean =
        publishedSequences.get(pos) == REPLIED

    private def isAvailable(pos: Int): Boolean =
        publishedSequences.get(pos) == AVAILABLE

    private def position(seq: Long): Int =
        seq.toInt & mask
}
