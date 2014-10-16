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
import org.midonet.sdn.flows.FlowTagger

object PoolManager {
    case class TriggerUpdate(poolMembers: Set[PoolMember])

    private def toSimulationPoolMember(pm: PoolMember): simulation.PoolMember =
        new simulation.PoolMember(
            pm.getId,
            IPv4Addr(pm.getAddress),
            pm.getProtocolPort,
            pm.getWeight)
}

class PoolManager(val id: UUID, val clusterClient: Client) extends Actor
    with ActorLogWithoutPath {
    import PoolManager._
    import context.system // Used implicitly. Don't delete.

    private var poolConfig: Pool = null
    private var simPoolMembers: Array[simulation.PoolMember] = null
    private var disabledPoolMembers: Array[simulation.PoolMember] = null

    override def preStart() {
        clusterClient.getPool(id, new PoolBuilderImpl(self))
    }

    private def updatePoolMembers(newMembers: Set[PoolMember]): Unit = {
        // Convert to simulation objects before creating Pool
        simPoolMembers = newMembers.collect {
            case pm if pm.isUp => toSimulationPoolMember(pm)
        } (breakOut(Array.canBuildFrom))
        disabledPoolMembers = newMembers.collect {
            case pm if !pm.getAdminStateUp => toSimulationPoolMember(pm)
        } (breakOut(Array.canBuildFrom))
        publishUpdateIfReady()
    }

    private def updateConfig(pool: Pool) {
        poolConfig = pool
        publishUpdateIfReady()
    }

    private def publishUpdateIfReady() {
        if (simPoolMembers == null || disabledPoolMembers == null) {
            log.debug(s"Not publishing pool $id. Still waiting for pool members.")
            return
        }
        if (poolConfig == null) {
            log.debug(s"Not publishing pool $id. Still waiting for pool config.")
            return
        }
        log.debug(s"Publishing update for pool $id.")

        val simPool = new simulation.Pool(
            id, poolConfig.isAdminStateUp, poolConfig.getLbMethod,
            simPoolMembers, disabledPoolMembers)
        VirtualTopologyActor ! simPool
        VirtualTopologyActor ! InvalidateFlowsByTag(FlowTagger.tagForDevice(id))
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
