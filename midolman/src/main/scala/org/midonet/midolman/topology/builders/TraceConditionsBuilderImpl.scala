// Copyright 2013 Midokura Inc.

package org.midonet.midolman.topology.builders

import akka.actor.ActorRef
import java.util.Collections.emptyList
import java.util.List

import org.midonet.cluster.client.TraceConditionsBuilder
import org.midonet.midolman.rules.Condition
import org.midonet.midolman.topology.TraceConditionsManager.TriggerUpdate


class TraceConditionsBuilderImpl(val traceConditionsMgr: ActorRef)
        extends TraceConditionsBuilder {
    private var conditionList: List[Condition] = emptyList()

    override def setConditions(conditions: List[Condition]) {
        conditionList = conditions
    }

    override def build() {
        traceConditionsMgr ! TriggerUpdate(conditionList)
    }
}
