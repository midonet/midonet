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

import java.util.{ArrayList, Collection}

import org.midonet.midolman.flows.FlowExpirationIndexer.Expiration
import org.midonet.odp.FlowMatch
import org.midonet.sdn.flows.FlowTagger.FlowTag
import org.midonet.util.collection.{ObjectPool, PooledObject}
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
    var sequence = 0L

    def reset(flowMatch: FlowMatch, flowTags: Collection[FlowTag],
              flowRemovedCallbacks: ArrayList[Callback0], sequence: Long,
              expiration: Expiration, now: Long): Unit = {
        this.flowMatch.resetWithoutUserspaceFields(flowMatch)
        expirationType = expiration.typeId
        absoluteExpirationNanos = now + expiration.value
        tags.addAll(flowTags)
        callbacks.addAll(flowRemovedCallbacks)
        this.sequence = sequence
    }

    override def clear(): Unit = {
        flowMatch.clear()
        callbacks.clear()
        tags.clear()
    }

    override def toString: String = {
        "ManagedFlow{" +
            "objref=" + System.identityHashCode(this) +
            ", flowMatch=" + flowMatch +
            ", flowMatch hash=" + flowMatch.hashCode() +
            ", sequence=" + sequence +
            ", refs=" + currentRefCount +
            '}'
    }
}
