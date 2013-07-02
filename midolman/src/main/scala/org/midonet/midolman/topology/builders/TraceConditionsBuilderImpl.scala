// Copyright 2013 Midokura Inc.

package org.midonet.midolman.topology.builders

import akka.actor.ActorRef
import java.util.Collections.emptySet
import java.util.Set

import org.midonet.cluster.client.TraceConditionsBuilder
import org.midonet.midolman.rules.Condition
import org.midonet.midolman.topology.TraceConditionsManager.TriggerUpdate


class TraceConditionsBuilderImpl(val traceConditionsMgr: ActorRef)
        extends TraceConditionsBuilder {
    private var conditionSet: Set[Condition] = emptySet()

    override def setConditions(conditions: Set[Condition]) {
        conditionSet = conditions
    }

    override def build() {
        traceConditionsMgr ! TriggerUpdate(conditionSet)
    }
}
