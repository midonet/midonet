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

import org.midonet.util.concurrent.NanoClock

trait TimedBlockHeader extends BlockHeader {
    def lastEntryTime: Long
}

/**
  * This trait allows a [[ByteBufferBlockWriter]] to handle block
  * expirations according to a specified expiration time. The expired block
  * is returned to the block pool so it can be reused later on if needed.
  * The expiration time is a soft deadline meaning that blocks can be
  * released after the deadline is due as we need to explicitly call
  * the invalidateBlocks method.
  */
trait TimedBlockInvalidator[T <: TimedBlockHeader] { this: ByteBufferBlockWriter[T] =>

    private val clock = NanoClock.DEFAULT

    /**
      * This method triggers and invalidates the entries in the ring buffer
      * that are older than a configured amount of time. We do not invalidate
      * the head of the ring buffer as it's the block being written.
      *
      * @return The number of blocks invalidated
      */
    def invalidateBlocks(): Int = {
        val numBlocks = buffers.length
        while (buffers.length > 1 &&
               isBlockStale(blockBuilder(buffers.peek.get), currentClock)) {
            val bb = buffers.take().get
            // Reset the block to initial values to prepare them for reuse
            // and not confuse as a valid block when reading it.
            blockBuilder.reset(bb)
        }
        numBlocks - buffers.length
    }

    @inline
    private[stream] def currentClock = clock.tick

    @inline
    private def isBlockStale(blockHeader: TimedBlockHeader, tick: Long): Boolean = {
        tick - blockHeader.lastEntryTime > expirationTime
    }

}
