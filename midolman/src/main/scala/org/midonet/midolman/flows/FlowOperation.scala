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

package org.midonet.midolman.flows

import java.nio.ByteBuffer
import java.util.Queue

import org.midonet.netlink.exceptions.NetlinkException
import org.midonet.odp.{FlowMatch, FlowMetadata}
import org.midonet.util.collection.ObjectPool
import rx.Observer

object FlowOperation {
    val GET: Byte = 0
    val DELETE: Byte = 1
}

final class FlowOperation(pool: ObjectPool[FlowOperation],
                          completedRequests: Queue[FlowOperation])
    extends Observer[ByteBuffer] {

    val flowMetadata = new FlowMetadata()

    var opId: Byte = _
    var flowMatch: FlowMatch = new FlowMatch
    var sequence: Long = -1
    var retries: Byte = _
    var failure: Throwable = _

    def isFailed = failure ne null

    def reset(opId: Byte, flowMatch: FlowMatch,
              sequence: Long, retries: Byte): Unit = {
        this.opId = opId
        reset(flowMatch, sequence, retries)
    }

    def reset(flowMatch: FlowMatch, sequence: Long, retries: Byte): Unit = {
        this.flowMatch.reset(flowMatch)
        this.sequence = sequence
        this.retries = retries
    }

    def netlinkErrorCode =
        failure match {
            case ex: NetlinkException => ex.getErrorCodeEnum
            case _ => NetlinkException.GENERIC_IO_ERROR
        }

    def clear(): Unit = {
        failure = null
        sequence = -1
        flowMatch.clear()
        flowMetadata.clear()
        pool.offer(this)
    }

    override def onNext(t: ByteBuffer): Unit =
        try {
            flowMetadata.deserialize(t)
        } catch { case e: Throwable =>
            failure = e
        }

    override def onCompleted(): Unit = {
        completedRequests.offer(this)
    }

    override def onError(e: Throwable): Unit = {
        failure = e
        onCompleted()
    }
}
