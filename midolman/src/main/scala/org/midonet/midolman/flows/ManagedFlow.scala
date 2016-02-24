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

/**
 * A ManagedFlow that is stored in a pool.
 * Once the instance is no longer used, the pool entry can be reused.
 *
 * Users should refrain from changing attributes after resetting.
 */
final class ManagedFlow(override val pool: ObjectPool[ManagedFlow])
        extends PooledObject {

    val callbacks = new ArrayList[Callback0]()
    val tags = new ArrayList[FlowTag]
    val flowMatch = new FlowMatch()
    var expirationType = 0
    var absoluteExpirationNanos = 0L
    // To synchronize create operation with delete operations
    var sequence = 0L
    // To access this object from a netlink sequence number, used for duplicate detection
    var mark = 0
    var removed = true
    var linkedFlow: ManagedFlow = null

    def reset(flowMatch: FlowMatch, flowTags: ArrayList[FlowTag],
              flowRemovedCallbacks: ArrayList[Callback0], sequence: Long,
              expiration: Expiration, now: Long, linkedFlow: ManagedFlow = null): Unit = {
        this.flowMatch.resetWithoutIcmpData(flowMatch)
        expirationType = expiration.typeId
        absoluteExpirationNanos = now + expiration.value
        ArrayListUtil.addAll(flowTags, tags)
        ArrayListUtil.addAll(flowRemovedCallbacks, callbacks)
        this.sequence = sequence
        this.linkedFlow = linkedFlow
        removed = false
    }

    def assignSequence(seq: Long): Unit = {
        sequence = seq
        if (linkedFlow ne null) {
            linkedFlow.sequence = seq
        }
    }

    override def clear(): Unit = {
        flowMatch.clear()
        callbacks.clear()
        tags.clear()
    }

    override def toString: String = {
        "ManagedFlow{" +
            s"objref=${System.identityHashCode(this)}" +
            s", flowMatch=$flowMatch" +
            s", flowMatch hash=${flowMatch.hashCode()}" +
            s", sequence=$sequence" +
            s", refs=$currentRefCount" +
            (if (linkedFlow ne null) s", linked flow=$linkedFlow" else "") +
            '}'
    }
}
