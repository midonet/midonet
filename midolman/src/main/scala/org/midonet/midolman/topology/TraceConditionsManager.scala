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
package org.midonet.midolman.topology

import scala.collection.JavaConverters._
import akka.actor.Actor
import java.util.{List => JList, UUID}

import org.midonet.cluster.Client
import org.midonet.midolman.logging.ActorLogWithoutPath
import org.midonet.midolman.rules.Condition
import org.midonet.midolman.topology.builders.TraceConditionsBuilderImpl
import org.midonet.midolman.topology.rcu.TraceConditions

object TraceConditionsManager {
    case class TriggerUpdate(conditions: JList[Condition])
    val uuid = UUID.fromString("11111111-2222-3333-4444-555555555555")
    def getUuid = uuid
}

class TraceConditionsManager(val id: UUID, val clusterClient: Client)
        extends Actor with ActorLogWithoutPath {
    import TraceConditionsManager._
    import context.system

    override def preStart() {
        log.debug("Bringing up TraceConditionsManager with client {}",
                  clusterClient)
        if (id == uuid) {
            clusterClient.getTraceConditions(
                new TraceConditionsBuilderImpl(self))
        } else {
            log.error("Requested bad ID {} for the trace condition set", id)
        }
    }

    override def receive = {
        case TriggerUpdate(conditions) =>
            log.debug("Got update to conditions [{}], sending to VTA {}.",
                      conditions, VirtualTopologyActor)
            VirtualTopologyActor ! TraceConditions(conditions.asScala.toList)
    }
}
