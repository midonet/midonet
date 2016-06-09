/*
 * Copyright 2016 Midokura SARL
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

package org.midonet.util.io.stream

import org.midonet.util.concurrent.NanoClock.{DEFAULT => clock}

trait ExpirationBlockHeader extends BlockHeader {
    def lastEntryTime: Long
}

/**
  * This trait allows a [[ByteBufferBlockStream]] to handle block
  * expirations according to a specified expiration time. The expired block
  * is returned to the block pool so it can be reused later on if needed.
  * The expiration time is a soft deadline meaning that blocks can be
  * released after the deadline is due as we need to explicitly call
  * the invalidateBlocks method.
  */
trait ExpiringBlockStream
    extends ByteBufferBlockStream[ExpirationBlockHeader] {

    /** Expiration time in nanoseconds, same precision as used by [[clock]]. */
    val expirationTime: Long

    /**
      * This method triggers and invalidates the entries in the stream that
      * are older than a configured amount of time.
      */
    def invalidateBlocks(): Unit = {
        val tick = clock.tick
        val numBlocks = buffers.length
        while (buffers.nonEmpty
               && isBlockStale(blockBuilder(buffers.front), tick)) {
            val bb = buffers.dequeue()
            bufferPool.offer(bb)
        }

        if (numBlocks - buffers.length > 0) {
            log.debug(s"Released ${numBlocks - buffers.length} out of " +
                      s"$numBlocks blocks with stale entries.")
        }
    }

    @inline
    private def isBlockStale(blockHeader: ExpirationBlockHeader, tick: Long): Boolean = {
        val isBlockStale = tick - blockHeader.lastEntryTime > expirationTime
        if (isBlockStale) log.debug(s"Block $blockHeader expired.")
        isBlockStale
    }

}
