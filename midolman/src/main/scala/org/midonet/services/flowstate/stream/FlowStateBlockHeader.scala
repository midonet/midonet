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

package org.midonet.services.flowstate.stream

import java.nio.ByteBuffer
import java.util

import org.midonet.util.concurrent.NanoClock
import org.midonet.util.io.stream.{BlockHeaderBuilder, TimedBlockHeader}

case class FlowStateBlockHeader(val magic: Array[Byte],
                                override val blockLength: Int,
                                override val lastEntryTime: Long)
    extends TimedBlockHeader {


    override def toString: String = {
        s"${classOf[FlowStateBlockHeader].toString}[" +
        s"magic: ${new String(magic)}, " +
        s"last: $lastEntryTime, " +
        s"length: $blockLength]"
    }

    def isValid: Boolean = {
        util.Arrays.equals(magic, FlowStateBlock.MagicNumber) &&
        lastEntryTime != -1
    }
}

object FlowStateBlock extends BlockHeaderBuilder[TimedBlockHeader] {

    /**
      * Frame format for flow state storage blocks:
      * A message timestamp of -1 or without the
      * magic number means an invalid block.
      *
      * +---------------------------------------+
      * | Magic number (10 bytes if SHA-1)      |
      * +---------------------------------------+
      * | Length of compressed data (4 bytes)   |
      * +---------------------------------------+
      * | Last message timestamp (8 bytes)      |
      * +---------------------------------------+
      * | Data                                  |
      * |                                       |
      * |                                       |
      * +---------------------------------------+
      */

    val MagicNumber: Array[Byte] =
        Array[Byte]('X', 'F', 'l', 'o', 'w', 'S', 't', 'a', 't', 'e')
    val MagicNumberSize = MagicNumber.array.length
    val LengthOffset = MagicNumberSize
    val LastTimeOffset = MagicNumberSize + 4

    val headerSize = MagicNumberSize + 12

    override def init(buffer: ByteBuffer): Unit = {
        val header = FlowStateBlock(buffer)
        if (!header.isValid) {
            reset(buffer)
        } else {
            buffer.position(header.blockLength + headerSize)
        }
    }

    override def reset(buffer: ByteBuffer): Unit = {
        buffer.put(MagicNumber.array, 0, MagicNumberSize)
        buffer.putInt(LengthOffset, 0)
        buffer.putLong(LastTimeOffset, -1)
        buffer.position(headerSize)
    }

    override def update(buffer: ByteBuffer, params: AnyVal*): Unit = {
        buffer.putInt(LengthOffset, buffer.position - headerSize)
        buffer.putLong(LastTimeOffset, NanoClock.DEFAULT.tick)
    }

    override def apply(buffer: ByteBuffer): FlowStateBlockHeader = {
        val magic = new Array[Byte](MagicNumberSize)
        for (i <- 0 until MagicNumberSize) {
            magic(i) = buffer.get(i)
        }
        FlowStateBlockHeader(magic,
                             buffer.getInt(LengthOffset),
                             buffer.getLong(LastTimeOffset))
    }
}
