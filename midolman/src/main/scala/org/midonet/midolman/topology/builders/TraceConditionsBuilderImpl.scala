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
