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

import org.apache.commons.lang.builder.HashCodeBuilder

import org.midonet.midolman.CallbackExecutor
import org.midonet.midolman.simulation.PacketContext
import org.midonet.odp.FlowMatch
import org.midonet.sdn.flows.FlowTagger.FlowTag
import org.midonet.util.collection.{ObjectPool, PooledObject}
import org.midonet.util.functors.Callback0

abstract class WildcardFlow {
    def flowMatch: FlowMatch
    def hardExpirationMillis: Int
    def idleExpirationMillis: Int
    def cbExecutor: CallbackExecutor

    def getMatch = flowMatch
    def getHardExpirationMillis = hardExpirationMillis
    def getIdleExpirationMillis = idleExpirationMillis

    override def toString: String = {
        "WildcardFlow{" +
            "wcmatch=" + flowMatch +
            ", hardExpirationMillis=" + hardExpirationMillis +
            ", idleExpirationMillis=" + idleExpirationMillis +
            '}'
    }

    // CAREFUL: this class is used as a key in various maps. DO NOT use
    // mutable fields when constructing the hashCode or evaluating equality.
    override def equals(o: Any): Boolean = o match {
        case that: WildcardFlow if this eq that => true

        case that: WildcardFlow =>
            if (that.hardExpirationMillis != this.hardExpirationMillis ||
                that.idleExpirationMillis != this.idleExpirationMillis)
                return false

            flowMatch match {
                case null => if (that.flowMatch != null) return false
                case a =>    if (flowMatch != that.flowMatch) return false
            }
            true

        case _ => false
    }

    // CAREFUL: this class is used as a key in various maps. DO NOT use
    // mutable fields when constructing the hashCode or evaluating equality.
    override def hashCode(): Int = {
        new HashCodeBuilder(17, 37).
            append(hardExpirationMillis).
            append(idleExpirationMillis).
            append(flowMatch).
            toHashCode
    }
}

/**
 * A WildcardFlow that, for performance reasons, is stored in a pool.
 * Once the instance is no longer used, the pool entry can be reused.
 *
 * Users should refrain from changing attributes after resetting.
 */
class ManagedWildcardFlow(override val pool: ObjectPool[ManagedWildcardFlow])
        extends WildcardFlow with PooledObject {

    var creationTimeMillis: Long = 0L
    var lastUsedTimeMillis: Long = 0L
    val callbacks = new ArrayList[Callback0]()
    val tags = new ArrayList[FlowTag]

    val flowMatch = new FlowMatch()
    var hardExpirationMillis = 0
    var idleExpirationMillis = 0
    var cbExecutor: CallbackExecutor = _

    val INVALID_HASH_CODE = 0
    var cachedHashCode = INVALID_HASH_CODE

    def reset(pktCtx: PacketContext) = {
        this.flowMatch.reset(pktCtx.origMatch)
        this.hardExpirationMillis = pktCtx.hardExpirationMillis
        this.idleExpirationMillis = pktCtx.idleExpirationMillis
        this.cbExecutor = pktCtx.callbackExecutor
        this.tags.addAll(pktCtx.flowTags)
        this.callbacks.addAll(pktCtx.flowRemovedCallbacks)
        cachedHashCode = super.hashCode()
        this
    }

    def clear() {
        this.flowMatch.clear()
        this.callbacks.clear()
        this.tags.clear()
        cachedHashCode = INVALID_HASH_CODE
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
        if (cachedHashCode == INVALID_HASH_CODE)
            super.hashCode()
        else
            cachedHashCode

    override def toString: String = {
        "ManagedWildcardFlow{"+
            "objref=" + System.identityHashCode(this) +
            ", flowMatch=" + flowMatch +
            ", flowMatch hash=" + (if (flowMatch == null) ""
                                   else flowMatch.hashCode()) +
            ", creationTimeMillis=" + creationTimeMillis +
            ", lastUsedTimeMillis=" + lastUsedTimeMillis +
            ", hardExpirationMillis=" + hardExpirationMillis +
            ", idleExpirationMillis=" + idleExpirationMillis +
            '}'
    }
}
