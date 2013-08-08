// Copyright 2013 Midokura Inc.

package org.midonet.midolman.topology.builders

import akka.actor.ActorRef
import java.util.Collections.emptyList
import java.util.List
import org.slf4j.LoggerFactory

import org.midonet.cluster.client.TraceConditionsBuilder
import org.midonet.midolman.rules.Condition
import org.midonet.midolman.topology.TraceConditionsManager.TriggerUpdate


class TraceConditionsBuilderImpl(val traceConditionsMgr: ActorRef)
        extends TraceConditionsBuilder {
    private var conditionList: List[Condition] = emptyList()
    final val log = LoggerFactory.getLogger(classOf[TraceConditionsBuilderImpl])

    override def setConditions(conditions: List[Condition]) {
        log.debug("setConditions({})", conditions);
        conditionList = conditions
    }

    override def build() {
        log.debug("build - conditionList = {}", conditionList);
        traceConditionsMgr ! TriggerUpdate(conditionList)
    }
}
