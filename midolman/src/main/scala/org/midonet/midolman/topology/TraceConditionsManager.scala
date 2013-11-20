// Copyright 2013 Midokura Inc.

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
                val vta = VirtualTopologyActor.getRef()
                log.debug("Got update to conditions [{}], sending to VTA {}.",
                          conditions, vta)
                vta ! TraceConditions(conditions.asScala.toList)
    }
}
