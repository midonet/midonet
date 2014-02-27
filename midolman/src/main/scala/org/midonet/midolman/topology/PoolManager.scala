/*
 * Copyright (c) 2014 Midokura Europe SARL, All Rights Reserved.
 */
package org.midonet.midolman.topology

import akka.actor.{ActorRef, Actor}
import collection.JavaConverters._
import java.util.{Map => JMap, UUID}

import org.midonet.midolman.simulation.Pool
import org.midonet.cluster.Client
import org.midonet.cluster.client.PoolBuilder
import org.midonet.midolman.FlowController.InvalidateFlowsByTag
import org.midonet.midolman.logging.ActorLogWithoutPath
import org.midonet.cluster.data.l4lb.PoolMember
import org.midonet.midolman.simulation
import org.midonet.packets.IPv4Addr
import org.midonet.cluster.data.l4lb

object PoolManager {
    case class TriggerUpdate(poolMembers: Set[PoolMember])

    private def toSimulationPoolMember(pm: PoolMember): simulation.PoolMember =
        new simulation.PoolMember(
            pm.getId,
            pm.getAdminStateUp,
            IPv4Addr(pm.getAddress),
            pm.getProtocolPort,
            pm.getWeight,
            pm.getStatus)
}

class PoolManager(val id: UUID, val clusterClient: Client) extends Actor
    with ActorLogWithoutPath {
    import PoolManager._
    import context.system // Used implicitly. Don't delete.

    override def preStart() {
        clusterClient.getPool(id, new PoolBuilderImpl(self))
    }

    private def updatePoolMembers(curPoolMembers: Set[PoolMember]): Unit = {
        // Send the VirtualTopologyActor an updated pool.

        // Convert data objects to simulation objects before creating LoadBalancer
        val simulationPoolMembers = curPoolMembers.map(PoolManager.toSimulationPoolMember)

        publishUpdate(new Pool(id, simulationPoolMembers.toList,
            context.system.eventStream))
    }

    private def publishUpdate(pool: Pool) {
        VirtualTopologyActor ! pool
        VirtualTopologyActor ! InvalidateFlowsByTag(
            FlowTagger.invalidateFlowsByDevice(id))
    }

    override def receive = {
        case TriggerUpdate(poolMembers) => {
            log.debug("Update triggered for pool members of pool ID {}", id)
            updatePoolMembers(poolMembers)
        }
    }
}

class PoolBuilderImpl(val poolMgr: ActorRef)
    extends PoolBuilder {
    import PoolManager.TriggerUpdate

    def setPoolMembers(poolMemberMap: JMap[UUID, PoolMember]) {
        val poolMemberSet: Set[PoolMember] = poolMemberMap.values().asScala.toSet
        poolMgr ! TriggerUpdate(poolMemberSet)
    }
}
