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

import java.nio.ByteBuffer
import java.util.UUID

import scala.collection.mutable

import org.midonet.midolman.config.FlowStateConfig
import org.midonet.util.io.{ByteBufferFactory, InMemoryByteBufferFactory, ByteBufferPool}

object FlowStateBuffer {

    def apply(config: FlowStateConfig, byteBuffers: ByteBufferPool): FlowStateBuffer = {
        val out = new SnappyFlowStateOutputStream(config, byteBuffers)
        val in = new SnappyFlowStateInputStream(config, out.buffers)
        FlowStateBuffer(in, out)
    }

}

case class FlowStateBuffer(in: FlowStateInputStream,
                           out: FlowStateOutputStream) {

    def getByteBuffers: Seq[ByteBuffer] = out.buffers
}


class FlowStateStorageProvider(val config: FlowStateConfig) {

    private val byteBufferFactory: ByteBufferFactory =
        new InMemoryByteBufferFactory(config.blockSize, config.maxTotalSize)

    private val byteBuffers: ByteBufferPool =
        new ByteBufferPool(config.blockSize, config.maxTotalSize, byteBufferFactory)

    private val flowStateBuffers = new mutable.HashMap[UUID, FlowStateBuffer]

    def get(portId: UUID): FlowStateBuffer = {
       flowStateBuffers.get(portId) match {
           case Some(buff) => buff
           case None => FlowStateBuffer(config, byteBuffers)
       }
    }

    def remove(portId: UUID): Unit = {
        flowStateBuffers.remove(portId) match {
            case Some(FlowStateBuffer(in, out)) =>
                // Close stream and release buffers for this port
                out.close()
                out.buffers foreach byteBuffers.release
            case None =>
        }

    }

}
