/*
* Copyright 2013 Midokura Europe SARL
*/
package org.midonet.netlink;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A pool of reusable, native I/O ready, byte buffers. All operations are
 * guaranteed to be thread-safe and non-blocking.
 */
public class BufferPool {
    private static final Logger log = LoggerFactory
            .getLogger(BufferPool.class);

    private int maxBuffers;
    private int bufSize;

    private static final Object PRESENT = new Object();
    private ConcurrentMap<ByteBuffer, Object> bufferPool;
    private BlockingQueue<ByteBuffer> availPool;
    private AtomicInteger numBuffers;

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
        this.bufferPool = new ConcurrentHashMap<ByteBuffer, Object>(maxBuffers);

        numBuffers = new AtomicInteger(0);
        do {
            ByteBuffer buf = ByteBuffer.allocateDirect(bufSize);
            availPool.offer(buf);
            bufferPool.put(buf, PRESENT);
            numBuffers.incrementAndGet();
        } while (numBuffers.incrementAndGet() < minBuffers);
    }

    public ByteBuffer take() {
        ByteBuffer buffer = availPool.poll();
        if (buffer != null)
            return buffer;

        if (numBuffers.incrementAndGet() <= maxBuffers) {
            ByteBuffer buf = ByteBuffer.allocateDirect(bufSize);
            bufferPool.put(buf, PRESENT);
            return buf;
        } else {
            numBuffers.decrementAndGet();
            log.warn("Pool is empty, allocating a temporary buffer");
            return ByteBuffer.allocateDirect(bufSize);
        }
    }

    public void release(ByteBuffer buf) {
        if (bufferPool.containsKey(buf))
            availPool.offer(buf);
    }
}
