/*
 * Copyright 2013 Midokura Europe SARL
 */

package org.midonet.sdn.flows

import scala.collection.immutable.List
import scala.collection.{Set => ROSet}

import org.apache.commons.lang.builder.HashCodeBuilder

import org.midonet.odp.FlowMatch
import org.midonet.odp.flows.FlowAction
import org.midonet.util.collections.WeakObjectPool
import org.midonet.util.functors.Callback0

trait WildcardFlowBase {
    def priority: Short
    def wcmatch: WildcardMatch
    def actions: List[FlowAction[_]]

    def hardExpirationMillis: Int
    def idleExpirationMillis: Int

    def getPriority = priority
    def getMatch = wcmatch
    def getActions = actions
    def getHardExpirationMillis = hardExpirationMillis
    def getIdleExpirationMillis = idleExpirationMillis

    // CAREFUL: this class is used as a key in various maps. DO NOT use
    // mutable fields when constructing the hashCode or evaluating equality.
    override def equals(o: Any): Boolean = o match {
        case that: WildcardFlowBase if (this eq that) => true

        case that: WildcardFlowBase =>
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

    override def toString: String = {
        "WildcardFlowBase{" +
            "actions=" + actions +
            ", priority=" + priority +
            ", wcmatch=" + wcmatch +
            ", hardExpirationMillis=" + hardExpirationMillis +
            ", idleExpirationMillis=" + idleExpirationMillis +
            '}'
    }
}

class WildcardFlow(override val priority: Short,
                   override val wcmatch: WildcardMatch,
                   override val actions: List[FlowAction[_]],
                   override val hardExpirationMillis: Int,
                   override val idleExpirationMillis: Int,
                   var creationTimeMillis: Long = 0L,
                   var lastUsedTimeMillis: Long = 0L,
                   var callbacks: Array[Callback0] = new Array[Callback0](0),
                   var tags: Array[Any] = new Array[Any](0)) extends WildcardFlowBase {
    val dpFlows = new java.util.HashSet[FlowMatch](4)

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
        "WildcardFlow{" +
            "actions=" + actions +
            ", priority=" + priority +
            ", wcmatch=" + wcmatch +
            ", creationTimeMillis=" + creationTimeMillis +
            ", lastUsedTimeMillis=" + lastUsedTimeMillis +
            ", hardExpirationMillis=" + hardExpirationMillis +
            ", idleExpirationMillis=" + idleExpirationMillis +
            '}'
    }
}

object WildcardFlowBuilder {
    private val actionsPool = new WeakObjectPool[List[FlowAction[_]]]()

    def empty: WildcardFlowBuilder = new WildcardFlowBuilder()
}

class WildcardFlowBuilder(
        private[this] var _priority: Short = 0,
        private[this] var _wcmatch: WildcardMatch = new WildcardMatch(),
        private[this] var _actions: List[FlowAction[_]] = List(),
        private[this] var _hardExpirationMillis: Int = 0, // default: never expire
        private[this] var _idleExpirationMillis: Int = 0) extends WildcardFlowBase {

    import WildcardFlowBuilder._

    def this(base: WildcardFlow) = this(base.priority,
                                        base.wcmatch,
                                        base.actions,
                                        base.hardExpirationMillis,
                                        base.idleExpirationMillis)

    override def priority: Short = _priority
    def priority_=(v: Short) { _priority = v }

    override def wcmatch: WildcardMatch = _wcmatch
    def wcmatch_=(v: WildcardMatch) { _wcmatch = v }

    override def actions: List[FlowAction[_]] = _actions
    def actions_=(v: List[FlowAction[_]]) { _actions = v }

    override def hardExpirationMillis: Int = _hardExpirationMillis
    def hardExpirationMillis_=(v: Int) { _hardExpirationMillis = v }

    override def idleExpirationMillis: Int = _idleExpirationMillis
    def idleExpirationMillis_=(v: Int) { _idleExpirationMillis = v }

    def setPriority(priority: Short): WildcardFlowBuilder = {
        this.priority = priority
        this
    }

    def setMatch(wcmatch: WildcardMatch): WildcardFlowBuilder = {
        this.wcmatch = wcmatch
        this
    }

    def setActions(actions: List[FlowAction[_]]): WildcardFlowBuilder = {
        this.actions = actions
        this
    }

    def addAction(action: FlowAction[_]): WildcardFlowBuilder = {
        if (actions == null)
            actions = List[FlowAction[_]](action)
        else
            actions = actions :+ action
        this
    }

    def setHardExpirationMillis(hardExpirationMillis: Int): WildcardFlowBuilder = {
        this.hardExpirationMillis = hardExpirationMillis
        this
    }

    def setIdleExpirationMillis(idleExpirationMillis: Int): WildcardFlowBuilder = {
        this.idleExpirationMillis = idleExpirationMillis
        this
    }

    def build = new WildcardFlow(priority,
                        wcmatch,
                        if (actions.isEmpty) Nil else actionsPool.sharedRef(actions),
                        hardExpirationMillis,
                        idleExpirationMillis)
}
