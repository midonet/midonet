// Copyright 2013 Midokura Inc.

package org.midonet.midolman.topology

import akka.actor.{ActorRef, Actor}
import java.util.{List, UUID}

import org.midonet.cluster.Client
import org.midonet.midolman.logging.ActorLogWithoutPath
import org.midonet.midolman.rules.Condition
import org.midonet.midolman.topology.builders.TraceConditionsBuilderImpl


object TraceConditionsManager {
    case class TriggerUpdate(conditions: List[Condition])
    val uuid = UUID.fromString("11111111-2222-3333-4444-555555555555")
    def getUuid = uuid
}

class TraceConditionsManager(val id: UUID, val clusterClient: Client)
        extends Actor with ActorLogWithoutPath {
    import TraceConditionsManager._

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
                val vta = context.actorFor("..")
                log.debug("Got update to conditions [{}], sending to VTA {}.",
                          conditions, vta)
                vta ! TriggerUpdate(conditions)
    }
}
