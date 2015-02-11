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
    val FULL = 0
    private val UPCALL_SEQ = 0
    private val FREE = 0

    private val unsafe = Util.getUnsafe

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

    private val sequenceAddress = unsafe.objectFieldOffset(
        classOf[NetlinkRequestBroker].getDeclaredField("sequence"))

    private val BASE: Long = unsafe.arrayBaseOffset(classOf[Array[Int]])
    private val SCALE: Long = unsafe.arrayIndexScale(classOf[Array[Int]])
}

/**
 * Class through which to make Netlink requests that require a reply.
 * Thread-safe for concurrent callers of nextSequence/get/publishRequest,
 * writePublishedRequests and readReply, although the individual methods
 * are not thread-safe.
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
    private var sequence = 0

    /**
     * The highest written sequence. Confined to the writer thread.
     */
    private var writtenSequence = 0

    /**
     * The observers registered by the producer thread, through which the
     * reader thread will feed the replies.
     */
    private val observers = new Array[Observer[ByteBuffer]](capacity)

    /**
     * The expiration deadlines set by the producer thread and processed by
     * the reader thread.
     */
    private val expirations = new Array[Long](capacity)
    private val timeoutNanos = timeout.toNanos

    {
        var i = 0
        while (i < capacity) {
            expirations(i) = Long.MaxValue
            i += 1
        }
    }

    /**
     * The sequences that are awaiting replies.
     */
    private val sequences = new Array[Int](capacity)

    /**
     * This method returns true whether there may be some requests to write.
     * It is assumed that this method is called by the writer thread or by some
     * thread that synchronized with it (e.g., the WakerUpper).
     */
    def hasRequestsToWrite: Boolean =
        unsafe.getIntVolatile(this, sequenceAddress) != writtenSequence

    def publisherSequence = unsafe.getIntVolatile(this, sequenceAddress)
    def writerSequence = writtenSequence

    /**
     * Gets the next sequence available for publishing a request. This method
     * synchronizes with the reader thread by skipping over sequences that are
     * still awaiting a reply.
     */
    def nextSequence(): Int = {
        var seq = sequence
        if (seq == UPCALL_SEQ) {
            // It will take more than capacity iterations to circle back to
            // UPCALL_SEQ, so we only need to perform the check here.
            seq = UPCALL_SEQ + 1
        }
        val end = seq + capacity
        do {
            if (sequences(seq & mask) == FREE) {
                return seq
            }
            seq += 1
        } while (seq != end)
        FULL
    }

    /**
     * Returns the ByteBuffer corresponding to the specified sequence number.
     * The caller must serialize a valid Netlink request into this buffer. Note
     * that the sequence number will be written by the writer, so it need not
     * be filled by the caller.
     */
    def get(seq: Int): ByteBuffer =
        buffers(seq & mask)

    /**
     * Publishes a Netlink request, registering an Observer through which the
     * reply will be streamed. Synchronizes with the writer thread via the
     * sequenceAddress field and, transitively, with the reader thread.
     */
    def publishRequest(seq: Int, observer: Observer[ByteBuffer]): Unit = {
        val pos = seq & mask
        observers(pos) = observer
        sequences(pos) = seq
        unsafe.putOrderedInt(this, sequenceAddress, seq + 1)
    }

    /**
     * Writes all the new published requests. Returns the number of
     * bytes written.
     */
    def writePublishedRequests(): Int = {
        val publishedSeq = unsafe.getIntVolatile(this, sequenceAddress)
        var seq = writtenSequence

        if (publishedSeq == seq)
            return 0

        if (seq == UPCALL_SEQ)
            seq = UPCALL_SEQ + 1

        var nbytes = 0
        do {
            val pos = seq & mask
            // Check if the sequence was published or if it was jumped over
            // because the reply hasn't arrived yet.
            if (sequences(pos) == seq) {
                val buf = buffers(pos)
                try {
                    buf.putInt(buf.position() + NetlinkMessage.NLMSG_SEQ_OFFSET, seq)
                    expirations(pos) = clock.tick + timeoutNanos
                    nbytes += writer.write(buf)
                } catch { case e: Throwable =>
                    val obs = observers(pos)
                    freeSequence(pos)
                    obs.onError(e)
                } finally {
                    // We have to clear the buffer here instead of on handleReply()
                    // because IOUtil modifies the buffer's position after the write
                    // has been performed, racing with a concurrent handleReply().
                    // This can theoretically race with a call to get(), but we
                    // assume the write thread has made progress before we circle
                    // back to this position.
                    buf.clear()
                }
            }
            seq += 1
        } while (seq != publishedSeq)
        writtenSequence = seq
        nbytes
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
            readBuf.flip()
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
            val pos = seq & mask
            val obs = getObserver(pos, seq, unhandled)
            freeSequence(pos)
            obs.onError(e)
            0
        } finally {
            readBuf.clear()
            doTimeoutExpiration()
        }

    private def handleReply(reply: ByteBuffer, unhandled: Observer[ByteBuffer],
                            start: Int, size: Int): Unit = {
        val seq = readBuf.getInt(start + NetlinkMessage.NLMSG_SEQ_OFFSET)
        val pos = seq & mask
        val obs = getObserver(pos, seq, unhandled)

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

        freeSequence(pos)
        obs.onCompleted()
    }

    private def getObserver(pos: Int, seq: Int,
                            unhandled: Observer[ByteBuffer]): Observer[ByteBuffer] =
        if (seq == UPCALL_SEQ || sequences(pos) != seq) {
            unhandled
        } else {
            observers(pos)
        }

    private def doTimeoutExpiration(): Unit = {
        val currentTime = clock.tick
        var i = 0
        while (i < capacity) {
            if (sequences(i) != FREE && currentTime > expirations(i)) {
                val obs = observers(i)
                freeSequence(i)
                buffers(i).clear()
                obs.onError(timeoutException)
            }
            i += 1
        }
    }

    private def freeSequence(pos: Int): Unit = {
        // Synchronizes with the producer thread. For theoretical correctness,
        // the producer thread should do volatile reads of the contents of the
        // *sequences* array, but in practice, it's extremely unlikely for the
        // producer to not see this update due to having a previous value cached
        // in a register when it circles back to the *pos* position.
        observers(pos) = null
        expirations(pos) = Long.MaxValue
        unsafe.putOrderedInt(sequences, BASE + (pos * SCALE), FREE)
    }
}
