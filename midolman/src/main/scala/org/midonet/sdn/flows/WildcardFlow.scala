/*
 * Copyright 2013 Midokura Europe SARL
 */

package org.midonet.sdn.flows

import scala.collection.immutable.List
import scala.collection.JavaConverters._
import java.{util => ju}

import org.apache.commons.lang.builder.HashCodeBuilder

import org.midonet.odp.FlowMatch
import org.midonet.odp.flows.FlowAction
import org.midonet.util.collections.WeakObjectPool
import org.midonet.util.functors.Callback0
import org.midonet.util.collection.{ObjectPool, PooledObject}

object WildcardFlow {
    def apply(wcmatch: WildcardMatch,
                     actions: List[FlowAction[_]] = Nil,
                     hardExpirationMillis: Int = 0,
                     idleExpirationMillis: Int = 0,
                     priority: Short = 0) =
        new WildcardFlowImpl(wcmatch, actions, hardExpirationMillis,
                         idleExpirationMillis, priority)

    class WildcardFlowImpl(override val wcmatch: WildcardMatch,
                           override val actions: List[FlowAction[_]] = Nil,
                           override val hardExpirationMillis: Int = 0,
                           override val idleExpirationMillis: Int = 0,
                           override val priority: Short = 0) extends WildcardFlow {
    }
}

abstract class WildcardFlow {
    def wcmatch: WildcardMatch
    def actions: List[FlowAction[_]]
    def hardExpirationMillis: Int
    def idleExpirationMillis: Int
    def priority: Short

    def getPriority = priority
    def getMatch = wcmatch
    def getActions = actions
    def getHardExpirationMillis = hardExpirationMillis
    def getIdleExpirationMillis = idleExpirationMillis

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
    override def hashCode: Int = new HashCodeBuilder(17, 37).
        append(hardExpirationMillis).
        append(idleExpirationMillis).
        append(priority).
        append(actions).
        append(wcmatch).
        toHashCode
}

/**
* WildcardFlow factory for Java classes.
*/
object WildcardFlowFactory {
    def create(wcmatch: WildcardMatch): WildcardFlow =
        WildcardFlow(wcmatch)

    def create(wcmatch: WildcardMatch, actions: ju.List[FlowAction[_]]) =
        WildcardFlow(wcmatch, actions.asScala.toList)

    def createIdleExpiration(wcmatch: WildcardMatch, actions: ju.List[FlowAction[_]],
                             idleExpirationMillis: Int) =
        WildcardFlow(wcmatch, actions.asScala.toList,
            idleExpirationMillis = idleExpirationMillis)

    def createHardExpiration(wcmatch: WildcardMatch, actions: ju.List[FlowAction[_]],
                             hardExpirationMillis: Int) =
        WildcardFlow(wcmatch, actions.asScala.toList,
            hardExpirationMillis = hardExpirationMillis)

    def createIdleExpiration(wcmatch: WildcardMatch, idleExpirationMillis: Int) =
        WildcardFlow(wcmatch, idleExpirationMillis = idleExpirationMillis)

    def createHardExpiration(wcmatch: WildcardMatch, hardExpirationMillis: Int) =
        WildcardFlow(wcmatch, hardExpirationMillis = hardExpirationMillis)
}

object ManagedWildcardFlow {
    private val actionsPool = new WeakObjectPool[List[FlowAction[_]]]()

    def create(wildFlow: WildcardFlow) = new ManagedWildcardFlow(null).reset(wildFlow)
}

class ManagedWildcardFlow(override val pool: ObjectPool[ManagedWildcardFlow])
        extends WildcardFlow with PooledObject {

    override type PooledType = ManagedWildcardFlow
    override def self = this

    private[this] val _wcmatch: WildcardMatch = new WildcardMatch()
    private[this] var _actions: List[FlowAction[_]] = Nil
    private[this] var _hardExpirationMillis: Int = 0
    private[this] var _idleExpirationMillis: Int = 0
    private[this] var _priority: Short = 0

    var creationTimeMillis: Long = 0L
    var lastUsedTimeMillis: Long = 0L
    var callbacks: Array[Callback0] = null
    var tags: Array[Any] = null
    val dpFlows = new java.util.HashSet[FlowMatch](4)

    override def wcmatch = _wcmatch
    override def actions = _actions
    override def hardExpirationMillis = _hardExpirationMillis
    override def idleExpirationMillis = _idleExpirationMillis
    override def priority = _priority

    def reset(wflow: WildcardFlow): ManagedWildcardFlow = {
        this._wcmatch.reset(wflow.wcmatch)
        this._actions = ManagedWildcardFlow.actionsPool.sharedRef(wflow.actions)
        this._hardExpirationMillis = wflow.hardExpirationMillis
        this._idleExpirationMillis = wflow.idleExpirationMillis
        this._priority = wflow.priority
        this.dpFlows.clear()
        this
    }

    def clear() {
        this._wcmatch.clear()
        this._actions = Nil
        this.callbacks = null
        this.tags = null
        this.dpFlows.clear()
    }

    def immutable = WildcardFlow(_wcmatch, _actions, _hardExpirationMillis,
                                 _idleExpirationMillis, _priority)

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
