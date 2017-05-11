/*
 * Copyright 2014 Midokura SARL
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

import java.util.ArrayList

import org.midonet.midolman.flows.FlowExpirationIndexer.Expiration
import org.midonet.odp.FlowMatch
import org.midonet.sdn.flows.FlowTagger.FlowTag
import org.midonet.util.collection.{ArrayListUtil, ObjectPool, PooledObject}
import org.midonet.util.functors.Callback0

object ManagedFlow {
    type FlowId = Long
    val NoFlow: Long = -839193346820535158L
}

trait ManagedFlow {
    def flowMatch: FlowMatch

    /* The mark is an identifier shared by the flow and netlink.
     * The first 4 bits represent the worker id.
     * The next 28 bits represent the index of the flow in the flow table.
     * This means that a single worker can have a maximum of 2^28 flows at
     * any one time. Ideally the mark would be 64bit, but as we need to
     * use it in the netlink sequence id to match netlink requests to responses,
     * and netlink ids are 32bit, we are limited to 32bits.
     */
    def mark: Int

    /* The sequence is used to ensure that a delete request for a flow is not
     * issued before the create operation for that flow has been sent to the
     * kernel. This can occur because deletes and creates take a different path
     * to the kernel. While both deletes and creates are executed by the same
     * disruptor thread, they sent to the thread via different queues. Creates
     * go through the actual ringbuffer, while deletes are added directly to
     * another Array[ByteBuffer] using tryEject.
     * To ensure that the create has been processed, we keep a record of the
     * sequence in the ringbuffer that the create request was added to. Then
     * when adding the delete request for a flow we ensure that the sequence
     * that flow has been processed. Sequence is monotonically increasing
     * even though the ringbuffer itself loops around.
     * This design is a hangover from the time when the flow controller was
     * an akka actor, and thus ran in a separate thread to the packet workflow.
     * We should now be able to send deletes and create through the same
     * ringbuffer and remove the need for this sequencing. However this would
     * need to be heavily benchmarked if implemented to ensure it doesn't
     * introduce a perfomance regression.
     */
    def sequence: Long
    def assignSequence(seq: Long): Unit
}

/**
 * A ManagedFlow that is stored in a pool.
 * Once the instance is no longer used, the pool entry can be reused.
 *
 * Users should refrain from changing attributes after resetting.
 */
final class ManagedFlowImpl(override val pool: ObjectPool[ManagedFlowImpl])
        extends ManagedFlow with PooledObject {

    val callbacks = new ArrayList[Callback0]()
    val tags = new ArrayList[FlowTag]
    override val flowMatch = new FlowMatch()
    var expirationType = 0
    var absoluteExpirationNanos = 0L
    // To synchronize create operation with delete operations
    var _sequence = 0L
    // To access this object from a netlink sequence number, used for duplicate detection
    var _mark = 0

    var _id = -1L

    var linkedFlow: ManagedFlowImpl = null

    def reset(flowMatch: FlowMatch, flowTags: ArrayList[FlowTag],
              flowRemovedCallbacks: ArrayList[Callback0], sequence: Long,
              expiration: Expiration, now: Long,
              linkedFlow: ManagedFlowImpl = null): Unit = {
        this.flowMatch.resetWithoutIcmpData(flowMatch)
        expirationType = expiration.typeId
        absoluteExpirationNanos = now + expiration.value
        ArrayListUtil.addAll(flowTags, tags)
        ArrayListUtil.addAll(flowRemovedCallbacks, callbacks)
        this._sequence = sequence
        this.linkedFlow = linkedFlow
        this._id = -1L
    }

    def setId(id: Long): Unit = {
        _id = id
    }
    def id: Long = _id

    def setMark(m: Int): Unit = {
        _mark = m
    }
    override def mark: Int = _mark

    override def assignSequence(seq: Long): Unit = {
        _sequence = seq
        if (linkedFlow ne null) {
            linkedFlow._sequence = seq
        }
    }
    override def sequence: Long = _sequence

    override def clear(): Unit = {
        flowMatch.clear()
        callbacks.clear()
        tags.clear()
    }

    override def toString: String =
        toString(linkedFlow ne null)

    private def toString(printLinked: Boolean): String = {
        "ManagedFlow{" +
            s"objref=${System.identityHashCode(this)}" +
            s", flowMatch=$flowMatch" +
            s", flowMatch hash=${flowMatch.hashCode()}" +
            s", sequence=$sequence" +
            s", refs=$currentRefCount" +
            (if (printLinked) s", linked flow=${linkedFlow.toString(false)}" else "") +
            '}'
    }
}
