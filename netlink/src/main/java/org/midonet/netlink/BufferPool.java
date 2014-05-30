/*
* Copyright 2013 Midokura Europe SARL
*/
package org.midonet.netlink;

import com.google.common.collect.MapMaker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A pool of reusable, native I/O ready, byte buffers. All operations are
 * guaranteed to be thread-safe and non-blocking.
 */
public class BufferPool {

    private static final Logger log = LoggerFactory.getLogger(BufferPool.class);

    /** dummy object used as the unique value in the concurrent buffer "set". */
    private static final Object PRESENT = new Object();

    private final int maxBuffers;
    private final int bufSize;

    private final ConcurrentMap<ByteBuffer, Object> bufferPool;
    private final BlockingQueue<ByteBuffer> availPool;
    private final AtomicInteger numBuffers;

    /**
     * @param minBuffers Initial number of buffers to allocate in the pool.
     * @param maxBuffers Maximum number of buffers to manage. When a client asks
     *                   for a buffer and all buffers are taken by other clients,
     *                   new buffers will be created on demand up to maxBuffers.
     * @param bufSize
     */
    public BufferPool(int minBuffers, int maxBuffers, int bufSize) {
        if ((maxBuffers < minBuffers) || (maxBuffers <= 0) || (minBuffers < 0))
            throw new IllegalArgumentException();

        this.maxBuffers = maxBuffers;
        this.bufSize = bufSize;
        this.availPool = new ArrayBlockingQueue<ByteBuffer>(maxBuffers);

        /* Use a weak-keys map so that keys behave as in an identity HashMap.
        /*
        /* An identity map is needed because a ByteBuffer's hashCode() depends
        /* on the buffer's contents. A normal IdentityHashMap is not suitable
        /* because there's no concurrent implementation of it. Thus the use
        /* of the weak-keys concurrent map. */
        this.bufferPool = new MapMaker().
                            initialCapacity(maxBuffers).
                            weakKeys().
                            makeMap();

        numBuffers = new AtomicInteger(0);
        do {
            ByteBuffer buf = ByteBuffer.allocateDirect(bufSize);
            availPool.offer(buf);
            bufferPool.put(buf, PRESENT);
        } while (numBuffers.incrementAndGet() < minBuffers);
    }

    /** Take a byte buffer from the pool. The caller is responsible of calling
     *  release() once for the returned buffer to return it to the pool.
     */
    public ByteBuffer take() {
        ByteBuffer buffer = availPool.poll();
        if (buffer != null)
            return buffer;

        if (numBuffers.incrementAndGet() <= maxBuffers) {
            log.debug("increasing buffer pool size to {}", numBuffers.get());
            ByteBuffer buf = ByteBuffer.allocateDirect(bufSize);
            bufferPool.put(buf, PRESENT);
            return buf;
        } else {
            numBuffers.decrementAndGet();
            /* Temporary buffers are non-direct because the NIO library has its
             * own cache for them, managing this case more cleverly than we
             * we can from here. The library will get the buffer from its cache
             * when a write is requested, so it will be able to release it
             * immediately, whereas we would leave the task up to the garbage
             * collector.
             *
             * The price we pay for allocating a non-direct buffer is one extra
             * copy at write-time.
             */
            log.info("pool is empty, allocating a temporary buffer");
            return ByteBuffer.allocate(bufSize);
        }
    }

    /** Release a buffer that was previously taken from the pool.
     *
     *  NOTE: this method will assume that the given buffer is currently taken,
     *  callers must be careful not to call release() twice on the same buffer.
     */
    public void release(ByteBuffer buf) {
        if (buf != null && bufferPool.containsKey(buf)) {
            availPool.offer(buf);
            log.trace("released buffer ({}/{} free buffers)",
                     availPool.size(), numBuffers.get());
        }
    }

    public int available() {
        return availPool.size();
    }

    public int allocated() {
        return bufferPool.size();
    }
}
