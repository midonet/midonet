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
import java.{util => ju}

import scala.collection.JavaConverters._
import scala.collection.immutable.List

import org.apache.commons.lang.builder.HashCodeBuilder

import org.midonet.midolman.CallbackExecutor
import org.midonet.odp.FlowMatch
import org.midonet.odp.flows.FlowAction
import org.midonet.sdn.flows.FlowTagger.FlowTag
import org.midonet.util.collection.{ObjectPool, PooledObject, WeakObjectPool}
import org.midonet.util.functors.Callback0

object WildcardFlow {
    def apply(wcmatch: WildcardMatch,
              actions: List[FlowAction] = Nil,
              hardExpirationMillis: Int = 0,
              idleExpirationMillis: Int = 0,
              priority: Short = 0,
              cbExecutor: CallbackExecutor = CallbackExecutor.Immediate) =
        new WildcardFlowImpl(wcmatch, actions, hardExpirationMillis,
                             idleExpirationMillis, priority, cbExecutor)

    class WildcardFlowImpl(override val wcmatch: WildcardMatch,
                           override val actions: List[FlowAction] = Nil,
                           override val hardExpirationMillis: Int = 0,
                           override val idleExpirationMillis: Int = 0,
                           override val priority: Short = 0,
                           override val cbExecutor: CallbackExecutor) extends WildcardFlow
}

abstract class WildcardFlow {
    def wcmatch: WildcardMatch
    def actions: List[FlowAction]
    def hardExpirationMillis: Int
    def idleExpirationMillis: Int
    def priority: Short
    def cbExecutor: CallbackExecutor

    def getPriority = priority
    def getMatch = wcmatch
    def getActions = actions
    def getHardExpirationMillis = hardExpirationMillis
    def getIdleExpirationMillis = idleExpirationMillis

    def combine(that: WildcardFlow): WildcardFlow = {
        var hardExp = this.hardExpirationMillis.min(that.hardExpirationMillis)
        var idleExp = this.idleExpirationMillis.min(that.idleExpirationMillis)
        if (hardExp == 0) // try to get a positive value
            hardExp = this.hardExpirationMillis + that.hardExpirationMillis
        if (idleExp == 0) // try to get a positive value
            idleExp = this.idleExpirationMillis + that.idleExpirationMillis

        WildcardFlow(wcmatch = this.getMatch,
                     actions = this.actions ++ that.actions,
                     hardExpirationMillis = hardExp,
                     idleExpirationMillis = idleExp)
    }

    override def toString: String = {
        "WildcardFlow{" +
            "actions=" + actions +
            ", priority=" + priority +
            ", wcmatch=" + wcmatch +
            ", hardExpirationMillis=" + hardExpirationMillis +
            ", idleExpirationMillis=" + idleExpirationMillis +
            '}'
    }

    // CAREFUL: this class is used as a key in various maps. DO NOT use
    // mutable fields when constructing the hashCode or evaluating equality.
    override def equals(o: Any): Boolean = o match {
        case that: WildcardFlow if (this eq that) => true

        case that: WildcardFlow =>
            if (that.hardExpirationMillis != this.hardExpirationMillis ||
                that.idleExpirationMillis != this.idleExpirationMillis ||
                that.priority != this.priority)
                return false

            actions match {
                case null => if (that.actions != null) return false
                case a =>    if (actions != that.actions) return false
            }
            wcmatch match {
                case null => if (that.wcmatch != null) return false
                case a =>    if (wcmatch != that.wcmatch) return false
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
            append(priority).
            append(actions).
            append(wcmatch).
            toHashCode
    }
}

/**
 * WildcardFlow factory for Java classes.
 */
object WildcardFlowFactory {
    def create(wcmatch: WildcardMatch): WildcardFlow =
        WildcardFlow(wcmatch)

    def create(wcmatch: WildcardMatch, actions: ju.List[FlowAction]) =
        WildcardFlow(wcmatch, actions.asScala.toList)

    def createIdleExpiration(wcmatch: WildcardMatch, actions: ju.List[FlowAction],
                             idleExpirationMillis: Int) =
        WildcardFlow(wcmatch, actions.asScala.toList,
            idleExpirationMillis = idleExpirationMillis)

    def createHardExpiration(wcmatch: WildcardMatch, actions: ju.List[FlowAction],
                             hardExpirationMillis: Int) =
        WildcardFlow(wcmatch, actions.asScala.toList,
            hardExpirationMillis = hardExpirationMillis)

    def createIdleExpiration(wcmatch: WildcardMatch, idleExpirationMillis: Int) =
        WildcardFlow(wcmatch, idleExpirationMillis = idleExpirationMillis)

    def createHardExpiration(wcmatch: WildcardMatch, hardExpirationMillis: Int) =
        WildcardFlow(wcmatch, hardExpirationMillis = hardExpirationMillis)
}

object ManagedWildcardFlow {
    private val actionsPool = new WeakObjectPool[List[FlowAction]]()

    def create(wildFlow: WildcardFlow) = new ManagedWildcardFlow(null).reset(wildFlow)
}

/**
 * A WildcardFlow that, for performance reasons, is stored in a pool.
 * Once the instance is no longer used, the pool entry can be reused.
 *
 * Users should refrain from changing attributes after resetting.
 */
class ManagedWildcardFlow(override val pool: ObjectPool[ManagedWildcardFlow])
        extends WildcardFlow with PooledObject {

    override type PooledType = ManagedWildcardFlow
    override def self = this

    var creationTimeMillis: Long = 0L
    var lastUsedTimeMillis: Long = 0L
    var callbacks: ArrayList[Callback0] = null
    var tags: Array[FlowTag] = null
    val dpFlows = new java.util.HashSet[FlowMatch](4)

    val wcmatch = new WildcardMatch()
    var actions: List[FlowAction] = Nil
    var hardExpirationMillis = 0
    var idleExpirationMillis = 0
    var priority: Short = 0
    var cbExecutor: CallbackExecutor = _

    val INVALID_HASH_CODE = 0
    var cachedHashCode = INVALID_HASH_CODE

    def reset(wflow: WildcardFlow): ManagedWildcardFlow = {
        this.wcmatch.reset(wflow.wcmatch)
        this.actions = ManagedWildcardFlow.actionsPool.sharedRef(wflow.actions)
        this.hardExpirationMillis = wflow.hardExpirationMillis
        this.idleExpirationMillis = wflow.idleExpirationMillis
        this.priority = wflow.priority
        this.cbExecutor = wflow.cbExecutor
        this.dpFlows.clear()
        cachedHashCode = super.hashCode()
        this
    }

    def clear() {
        this.wcmatch.clear()
        this.actions = Nil
        this.callbacks = null
        this.tags = null
        this.dpFlows.clear()
        cachedHashCode = INVALID_HASH_CODE
    }

    def immutable = WildcardFlow(wcmatch, actions, hardExpirationMillis,
                                 idleExpirationMillis, priority)

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
            ", actions=" + actions +
            ", priority=" + priority +
            ", wcmatch=" + wcmatch +
            ", wcmatch hash=" + (if (wcmatch == null) ""
                                 else wcmatch.hashCode()) +
            ", creationTimeMillis=" + creationTimeMillis +
            ", lastUsedTimeMillis=" + lastUsedTimeMillis +
            ", hardExpirationMillis=" + hardExpirationMillis +
            ", idleExpirationMillis=" + idleExpirationMillis +
            '}'
    }
}
