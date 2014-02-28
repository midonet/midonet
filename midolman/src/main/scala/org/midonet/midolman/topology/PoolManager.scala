/*
 * Copyright (c) 2014 Midokura Europe SARL, All Rights Reserved.
 */
package org.midonet.midolman.topology

import akka.actor.{ActorRef, Actor}
import collection.JavaConverters._
import java.util.{Map => JMap, UUID}
import scala.collection.breakOut

import org.midonet.cluster.Client
import org.midonet.cluster.client.PoolBuilder
import org.midonet.midolman.FlowController.InvalidateFlowsByTag
import org.midonet.midolman.logging.ActorLogWithoutPath
import org.midonet.cluster.data.l4lb.{Pool, PoolMember}
import org.midonet.midolman.simulation
import org.midonet.packets.IPv4Addr

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

    private var poolConfig: Option[Pool] = None
    private var simPoolMembers: Option[List[simulation.PoolMember]] = None

    override def preStart() {
        clusterClient.getPool(id, new PoolBuilderImpl(self))
    }

    private def updatePoolMembers(curPoolMembers: Set[PoolMember]): Unit = {
        // Convert data objects to simulation objects before creating LoadBalancer
        simPoolMembers = Some(curPoolMembers.map
                                      (PoolManager.toSimulationPoolMember)
                                      (breakOut(List.canBuildFrom)))
        publishUpdateIfReady()
    }

    private def updateConfig(pool: Pool) {
        poolConfig = Some(pool)
        publishUpdateIfReady()
    }

    private def publishUpdateIfReady() {
        if (simPoolMembers.isEmpty) {
            log.debug(s"Not publishing pool $id. Still waiting for pool members.")
            return
        }
        if (poolConfig.isEmpty) {
            log.debug(s"Not publishing pool $id. Still waiting for pool config.")
            return
        }
        log.debug(s"Publishing update for pool $id.")

        val simPool = new simulation.Pool(
            id, poolConfig.get.isAdminStateUp, poolConfig.get.getLbMethod,
            simPoolMembers.get, context.system.eventStream)
        VirtualTopologyActor ! simPool
        VirtualTopologyActor ! InvalidateFlowsByTag(
            FlowTagger.invalidateFlowsByDevice(id))
    }

    override def receive = {
        case TriggerUpdate(poolMembers) => {
            log.debug("Update triggered for pool members of pool ID {}", id)
            updatePoolMembers(poolMembers)
        }
        case pool: Pool => {
            log.debug("Update triggered for config of pool ID {}", id)
            updateConfig(pool)
        }
    }
}

class PoolBuilderImpl(val poolMgr: ActorRef)
    extends PoolBuilder {
    import PoolManager.TriggerUpdate

    def setPoolConfig(pool: Pool) {
        poolMgr ! pool
    }

    def setPoolMembers(poolMemberMap: JMap[UUID, PoolMember]) {
        poolMgr ! TriggerUpdate(poolMemberMap.values().asScala.toSet)
    }
}
