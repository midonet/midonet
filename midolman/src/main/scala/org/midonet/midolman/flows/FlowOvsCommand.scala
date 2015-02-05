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

import akka.actor.ActorSystem

import org.jctools.queues.SpscArrayQueue

import rx.Observer

import org.midonet.midolman.FlowController
import org.midonet.netlink.BytesUtil
import org.midonet.netlink.exceptions.NetlinkException
import org.midonet.odp.{FlowMetadata, OvsProtocol}
import org.midonet.sdn.flows.ManagedFlow
import org.midonet.util.collection.ObjectPool

sealed abstract class FlowOvsCommand[T >: Null](completedRequests: SpscArrayQueue[T],
                                                pool: ObjectPool[T])
                                               (implicit actorSystem: ActorSystem)
    extends Observer[ByteBuffer] { self: T =>

    private val requestBuf = BytesUtil.instance.allocateDirect(2*1024)
    val flowMetadata = new FlowMetadata()
    var managedFlow: ManagedFlow = _

    var failure: Throwable = _

    def isFailed = failure ne null

    def netlinkErrorCode =
        failure match {
            case ex: NetlinkException => ex.getErrorCodeEnum
            case _ => NetlinkException.GENERIC_IO_ERROR
        }

     def reset(managedFlow: ManagedFlow): Unit = {
        this.managedFlow = managedFlow
        managedFlow.ref()
    }

    def clear(): Unit = {
        failure = null
        managedFlow.unref()
        managedFlow = null
        pool.offer(this)
    }

    def prepareRequest(datapathId: Int, protocol: OvsProtocol): ByteBuffer = {
        requestBuf.clear()
        prepareRequest(requestBuf, datapathId, protocol)
        requestBuf
    }

    protected def prepareRequest(buf: ByteBuffer, datapathId: Int,
                                 protocol: OvsProtocol): Unit

    final override def onNext(t: ByteBuffer): Unit =
        flowMetadata.deserialize(t)

    final override def onCompleted(): Unit = {
        completedRequests.offer(this)
        FlowController ! FlowController.CheckCompletedRequests
    }

    final override def onError(e: Throwable): Unit = {
        failure = e
        onCompleted()
    }
}

sealed class FlowRemoveCommand(pool: ObjectPool[FlowRemoveCommand],
                               completedRequests: SpscArrayQueue[FlowRemoveCommand])
                              (implicit actorSystem: ActorSystem)
    extends FlowOvsCommand[FlowRemoveCommand](completedRequests, pool) {

    var retries: Int = 10

    override def clear(): Unit = {
        retries = 10
        super.clear()
    }

    override def prepareRequest(buf: ByteBuffer, datapathId: Int,
                                protocol: OvsProtocol): Unit =
        protocol.prepareFlowDelete(datapathId, managedFlow.flowMatch.getKeys, buf)
}

sealed class FlowGetCommand(pool: ObjectPool[FlowGetCommand],
                            completedRequests: SpscArrayQueue[FlowGetCommand])
                           (implicit actorSystem: ActorSystem)
    extends FlowOvsCommand[FlowGetCommand](completedRequests, pool) {

    override def prepareRequest(buf: ByteBuffer, datapathId: Int,
                                protocol: OvsProtocol): Unit =
        protocol.prepareFlowGet(datapathId, managedFlow.flowMatch, buf)
}
