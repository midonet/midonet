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

import akka.actor.{ActorRef, ActorSystem}
import org.midonet.midolman.CheckBackchannels
import org.midonet.netlink.exceptions.NetlinkException
import org.midonet.odp.FlowMetadata
import org.midonet.util.collection.ObjectPool
import rx.Observer

object FlowOperation {
    val GET: Byte = 0
    val DELETE: Byte = 1
}

final class FlowOperation(actor: ActorRef,
                          pool: ObjectPool[FlowOperation],
                          completedRequests: Queue[FlowOperation])
                         (implicit actorSystem: ActorSystem)
    extends Observer[ByteBuffer] {

    val flowMetadata = new FlowMetadata()

    var opId: Byte = _
    var managedFlow: ManagedFlow = _
    var retries: Byte = _
    var failure: Throwable = _

    def isFailed = failure ne null

    def reset(opId: Byte, managedFlow: ManagedFlow, retries: Byte): Unit = {
        this.opId = opId
        reset(managedFlow, retries)
    }

    def reset(managedFlow: ManagedFlow, retries: Byte): Unit = {
        this.managedFlow = managedFlow
        this.retries = retries
        managedFlow.ref()
    }

    def netlinkErrorCode =
        failure match {
            case ex: NetlinkException => ex.getErrorCodeEnum
            case _ => NetlinkException.GENERIC_IO_ERROR
        }

    def clear(): Unit = {
        failure = null
        managedFlow.unref()
        managedFlow = null
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
        actor ! CheckBackchannels
    }

    override def onError(e: Throwable): Unit = {
        failure = e
        onCompleted()
    }
}
