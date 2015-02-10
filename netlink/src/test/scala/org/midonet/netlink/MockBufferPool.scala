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
import java.util.concurrent.{ConcurrentMap, ArrayBlockingQueue}
import java.util.concurrent.atomic.AtomicInteger

import com.google.common.collect.MapMaker
import org.slf4j.LoggerFactory

object MockBufferPool {
    private val log = LoggerFactory.getLogger(classOf[BufferPool])
}

/**
 * Mocked BufferPool that avoids using ByteBuffer::allocateDirect.
 * @param minBuffers the minimum buffer size
 * @param maxBuffers the minimum buffer size
 * @param bufSize the buffer size to allocate
 */
class MockBufferPool(minBuffers: Int, maxBuffers: Int, bufSize: Int)
        extends BufferPool(minBuffers, maxBuffers, bufSize) {
    import MockBufferPool._

    if ((maxBuffers < minBuffers) || (maxBuffers <= 0) || (minBuffers < 0)) {
        throw new IllegalArgumentException()
    }

    private val availPool = new ArrayBlockingQueue[ByteBuffer](maxBuffers)
    private val bufferPool: ConcurrentMap[ByteBuffer, AnyRef] =
        new MapMaker().initialCapacity(maxBuffers).weakKeys().makeMap()
    private val numBuffers = new AtomicInteger(0)
    do {
        val buf = BytesUtil.instance.allocate(bufSize)
        availPool.offer(buf)
        bufferPool.put(buf, BufferPool.PRESENT)
    } while (numBuffers.incrementAndGet() < minBuffers)

    override def take(): ByteBuffer = {
        val buffer: ByteBuffer = availPool.poll
        if (buffer != null) {
            return buffer
        }
        if (numBuffers.incrementAndGet <= maxBuffers) {
            log.debug("increasing buffer pool size to {}", numBuffers.get)
            val buf: ByteBuffer = BytesUtil.instance.allocate(bufSize)
            bufferPool.put(buf, BufferPool.PRESENT)
            buf
        } else {
            numBuffers.decrementAndGet
            log.info("pool is empty, allocating a temporary buffer")
            BytesUtil.instance.allocate(bufSize)
        }
    }
}
