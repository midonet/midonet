// Copyright 2013 Midokura Inc.

package org.midonet.midolman.topology

import akka.actor.{ActorRef, Actor}
import java.util.{Set, UUID}

import org.midonet.cluster.Client
import org.midonet.midolman.logging.ActorLogWithoutPath
import org.midonet.midolman.rules.Condition
import org.midonet.midolman.topology.builders.TraceConditionsBuilderImpl


object TraceConditionsManager {
    case class TriggerUpdate(conditions: Set[Condition])
    val uuid = UUID.fromString("11111111-2222-3333-4444-555555555555")
    def getUuid = uuid
}

class TraceConditionsManager(val clusterClient: Client) extends Actor
        with ActorLogWithoutPath {
    import TraceConditionsManager._

    override def preStart() {
        clusterClient.getTraceConditions(new TraceConditionsBuilderImpl(self))
    }

    override def receive = {
        case TriggerUpdate(conditions) =>
                context.actorFor("..") ! TriggerUpdate(conditions)
    }
}
