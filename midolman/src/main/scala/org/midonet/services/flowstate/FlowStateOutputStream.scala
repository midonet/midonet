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

package org.midonet.services.flowstate

import java.io._
import java.nio.ByteBuffer

import scala.collection.mutable
import scala.util.control.NonFatal

import org.apache.zookeeper.server.ByteBufferOutputStream
import org.xerial.snappy.SnappyOutputStream

import org.midonet.midolman.config.FlowStateConfig
import org.midonet.packets.SbeEncoder
import org.midonet.util.io.ByteBufferPool

trait FlowStateOutputStream {

    val buffers: Seq[ByteBuffer]

    /**
      * Write the flow state message encoded by the 'encoder' into the
      * data stream.
      */
    @throws[IOException]
    def write(encoder: SbeEncoder): Unit

    /**
      * Closes the underlying output stream and frees any resource used.
      */
    def close(): Unit

}

class ByteBufferFlowStateOutputStream(bb: ByteBuffer)
    extends ByteBufferOutputStream(bb) {

    override def write(buff: Array[Byte], offset: Int, length: Int): Unit = {
        if (length > bb.remaining()) {
            throw new EOFException()
        } else {
            bb.put(buff, offset, length)
        }
    }

}

class SnappyFlowStateOutputStream(config: FlowStateConfig, byteBuffers: ByteBufferPool)
    extends FlowStateOutputStream {

    override val buffers = mutable.MutableList.empty[ByteBuffer]

    var out: SnappyOutputStream = _

    /**
      * Write the flow state message encoded by the 'encoder' into the
      * data stream.
      */
    override def write(encoder: SbeEncoder): Unit = {
        // Missing all the logic to handle two minute expiration here
        // and block removal
        try {
            out.write(encoder.flowStateBuffer.array(),
                      0, encoder.encodedLength())
        } catch {
            case _ : NullPointerException | _ : EOFException =>
                allocateNewBlock()
                write(encoder)
            case NonFatal(e) =>
                // TODO: log error
        }
    }

    override def close(): Unit = {
        out.close()
    }

    private def allocateNewBlock() = {
        val byteBuffer = byteBuffers.acquire()
        val byteBufferStream = new ByteBufferFlowStateOutputStream(byteBuffer)
        buffers += byteBuffer
        out = new SnappyOutputStream(byteBufferStream)
    }
}


