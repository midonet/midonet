/*
 * Copyright 2015 Midokura SARL
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

package org.midonet.midolman.topology.devices

import java.util.{ArrayList, Collection, HashMap, List, Map, UUID}

import org.midonet.midolman.rules.{Condition, Rule, TraceRule, JumpRule}
import org.midonet.midolman.simulation.Chain

object TraceChain {
    def addJumpRule(traceRules: Collection[TraceRule],
                    jumpTarget: Option[Chain]): List[Rule] = {
        val rules = new ArrayList[Rule]
        rules.addAll(traceRules)
        jumpTarget.foreach((c: Chain) => {
                               rules.add(new JumpRule(new Condition(),
                                                      c.id, c.name))
                           })
        rules
    }

    def buildJumpMap(jumpTarget: Option[Chain]): Map[UUID, Chain] = {
        jumpTarget match {
            case Some(c) =>
                val map = new HashMap[UUID,Chain]()
                map.put(c.id, c)
                map
            case None => new HashMap[UUID,Chain]()
        }
    }
}

class TraceChain(id: UUID, traceRules: Collection[TraceRule], jumpTarget: Option[Chain])
        extends Chain(id, TraceChain.addJumpRule(traceRules, jumpTarget),
                      TraceChain.buildJumpMap(jumpTarget),
                      s"TRACE_REQUEST_CHAIN-$id") {
    def getId: UUID = id
    def hasTracesEnabled: Boolean = traceRules.size > 0

    override def toString =
        s"TraceChain [id=$id, numRules=${traceRules.size} jumpsTo=${jumpTarget}]"
}

