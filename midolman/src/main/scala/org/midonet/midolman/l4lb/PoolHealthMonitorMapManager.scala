/*
 * Copyright (c) 2014 Midokura Pte.Ltd.
 */
package org.midonet.midolman.l4lb

import akka.actor.Actor
import org.midonet.midolman.logging.ActorLogWithoutPath
import org.midonet.cluster.Client
import java.util.UUID
import org.midonet.midolman.topology.VirtualTopologyActor
import scala.collection.immutable.Map
import org.midonet.midolman.state.zkManagers.PoolHealthMonitorZkManager.PoolHealthMonitorConfig

object PoolHealthMonitorMapManager {
    case class TriggerUpdate(mappings: Map[UUID, PoolHealthMonitorConfig])
    case class PoolHealthMonitorMap(mappings: Map[UUID, PoolHealthMonitorConfig])
}

class PoolHealthMonitorMapManager(val client: Client)
        extends Actor with ActorLogWithoutPath {

    import PoolHealthMonitorMapManager._
    import context.system

    override def preStart() {
        client.getPoolHealthMonitorMap(
            new PoolHealthMonitorMapBuilderImpl(self))
    }

    def receive = {
        case TriggerUpdate(mappings) => {
            VirtualTopologyActor.getRef() ! PoolHealthMonitorMap(mappings)
        }
    }
}
