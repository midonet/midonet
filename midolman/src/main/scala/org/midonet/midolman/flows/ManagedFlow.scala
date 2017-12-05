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

    def mark: Int
    def setMark(m: Int): Unit

    def sequence: Long
    def assignSequence(seq: Long): Unit

    def ref(): Unit
    def unref(): Unit
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

    var removed = true
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
        removed = false
    }

    def setId(id: Long): Unit = {
        _id = id
    }
    def id: Long = _id

    override def setMark(m: Int): Unit = {
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
