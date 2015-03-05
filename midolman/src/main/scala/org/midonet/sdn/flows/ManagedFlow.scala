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
package org.midonet.sdn.flows

import java.util.ArrayList


import org.midonet.midolman.CallbackExecutor
import org.midonet.midolman.simulation.PacketContext
import org.midonet.odp.FlowMatch
import org.midonet.sdn.flows.FlowTagger.FlowTag
import org.midonet.util.collection.{ObjectPool, PooledObject}
import org.midonet.util.functors.Callback0

/**
 * A ManagedWildcardFlow that is stored in a pool.
 * Once the instance is no longer used, the pool entry can be reused.
 *
 * Users should refrain from changing attributes after resetting.
 */
final class ManagedFlow(override val pool: ObjectPool[ManagedFlow])
        extends PooledObject {

    var creationTimeMillis: Long = 0L
    var lastUsedTimeMillis: Long = 0L
    val callbacks = new ArrayList[Callback0]()
    val tags = new ArrayList[FlowTag]

    val flowMatch = new FlowMatch()
    var hardExpirationMillis = 0
    var cbExecutor: CallbackExecutor = _

    def reset(pktCtx: PacketContext) = {
        this.flowMatch.reset(pktCtx.origMatch)
        this.hardExpirationMillis = pktCtx.expiration
        this.cbExecutor = pktCtx.callbackExecutor
        this.tags.addAll(pktCtx.flowTags)
        this.callbacks.addAll(pktCtx.flowRemovedCallbacks)
        this
    }

    def clear(): Unit = {
        this.flowMatch.clear()
        this.callbacks.clear()
        this.tags.clear()
    }

    def getLastUsedTimeMillis = lastUsedTimeMillis
    def getCreationTimeMillis = creationTimeMillis

    def setLastUsedTimeMillis(lastUsedTimeMillis: Long): this.type = {
        this.lastUsedTimeMillis = lastUsedTimeMillis
        this
    }

    def setCreationTimeMillis(creationTimeMillis: Long): this.type = {
        this.creationTimeMillis = creationTimeMillis
        this
    }

    override def hashCode(): Int =
       flowMatch.hashCode()

    override def toString: String = {
        "ManagedFlow{"+
            "objref=" + System.identityHashCode(this) +
            ", flowMatch=" + flowMatch +
            ", flowMatch hash=" + flowMatch.hashCode() +
            ", creationTimeMillis=" + creationTimeMillis +
            ", lastUsedTimeMillis=" + lastUsedTimeMillis +
            ", hardExpirationMillis=" + hardExpirationMillis +
            ", refs=" + currentRefCount +
            '}'
    }

    override def equals(other: Any): Boolean = other match {
        case that: ManagedFlow => that.flowMatch.equals(flowMatch)
        case _ => false
    }
}
