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
package org.midonet.midolman.l4lb

import java.util.UUID

import scala.collection.immutable.{Map => IMap, Set => ISet}
import scala.collection.mutable
import scala.collection.mutable.{HashSet => MSet, Map => MMap}

import akka.actor.{ActorRef, Props}

import org.midonet.midolman.Referenceable
import org.midonet.midolman.logging.ActorLogWithoutPath
import org.midonet.midolman.simulation.{LoadBalancer => SimLoadBalancer, PoolMember => SimPoolMember, Vip => SimVip}
import org.midonet.midolman.state.l4lb.VipSessionPersistence
import org.midonet.midolman.topology.PoolHealthMonitorMapper.PoolHealthMonitorMapKey
import org.midonet.midolman.topology.TopologyActor
import org.midonet.midolman.topology.devices.{PoolHealthMonitor, PoolHealthMonitorMap}
import org.midonet.util.concurrent.ReactiveActor.{OnError, OnCompleted}

/**
 * This actor is responsible for providing the configuration data to
 * Health monitoring service.
 */
object HealthMonitorConfigWatcher {
    def props(fileLocs: String, suffix: String, manager: ActorRef): Props = {
        Props(new HealthMonitorConfigWatcher(fileLocs, suffix, manager))
    }

    def convertDataMapToConfigMap(
            map: IMap[UUID, PoolHealthMonitor],
            fileLocs: String, suffix: String):
                IMap[UUID, PoolConfig] = {
        val newMap = mutable.HashMap[UUID, PoolConfig]()

        map foreach {case (id: UUID, phm: PoolHealthMonitor) =>
                         newMap.put(id, convertDataToPoolConfig(id, fileLocs,
                             suffix, phm))}

        IMap(newMap.toSeq: _*)
    }

    val lbIdToRouterIdMap: MMap[UUID, UUID] = MMap.empty

    def getRouterId(loadBalancerId: UUID) =
        lbIdToRouterIdMap.get(loadBalancerId).orNull

    def convertDataToPoolConfig(poolId: UUID, fileLocs: String, suffix: String,
            data: PoolHealthMonitor): PoolConfig = {
        if (data == null) {
            null
        } else if (data.loadBalancer == null ||
            data.healthMonitor == null ) {
            null
        } else {
            val vips = new MSet[VipConfig]
            for (vip : SimVip <- data.vips) {
                vips add new VipConfig(vip.adminStateUp,
                                       vip.id,
                                       if (vip.address == null) null
                                       else vip.address.toString,
                                       vip.protocolPort,
                                       if (vip.isStickySourceIP)
                                           VipSessionPersistence.SOURCE_IP
                                       else null)
            }
            val hm = new HealthMonitorConfig(
                    data.healthMonitor.adminStateUp,
                    data.healthMonitor.delay,
                    data.healthMonitor.timeout,
                    data.healthMonitor.maxRetries)
            val members = new MSet[PoolMemberConfig]()
            for (member: SimPoolMember <- data.poolMembers) {
                members add new PoolMemberConfig(
                        member.adminStateUp,
                        member.id,
                        member.weight,
                        if (member.address == null) null
                        else member.address.toString,
                        member.protocolPort)
            }
            new PoolConfig(poolId, data.loadBalancer.id,
                    ISet(vips.toSeq:_*),
                    ISet(members.toSeq:_*), hm, true, fileLocs, suffix)
        }
    }

    // Notifies the watcher that it is now the haproxy node, and can send
    // updates regarding config changes.
    case object BecomeHaproxyNode
}

class HealthMonitorConfigWatcher(val fileLocs: String, val suffix: String,
                                 val manager: ActorRef)
        extends Referenceable with TopologyActor with ActorLogWithoutPath {

    import HealthMonitor._
    import HealthMonitorConfigWatcher._

    var poolIdtoConfigMap: IMap[UUID, PoolConfig] = IMap.empty

    var currentLeader: Boolean = false

    override val Name = "HealthMonitorConfigWatcher"

    override def logSource = "org.midonet.l4lb.health-monitor"

    override def preStart(): Unit = {
        subscribe[PoolHealthMonitorMap](PoolHealthMonitorMapKey)
    }

    private  def handleDeletedMapping(poolId: UUID) {
        log.debug("Pool {} mapping deleted", poolId)
        if (currentLeader)
            manager ! ConfigDeleted(poolId)
    }

    private def handleAddedMapping(poolId: UUID,
                                   data: PoolHealthMonitor) {
        log.debug("Pool {} mapping added: {}", poolId, data)
        val poolConfig = convertDataToPoolConfig(poolId, fileLocs, suffix, data)
        if (currentLeader) {
            manager ! ConfigAdded(poolId, poolConfig,
                getRouterId(poolConfig.loadBalancerId))
        }
    }

    private def handleUpdatedMapping(poolId: UUID, data: PoolConfig) {
        log.debug("Pool {} mapping updated: {}", poolId, data)
        if (currentLeader)
            manager ! ConfigUpdated(poolId, data,
                getRouterId(data.loadBalancerId))
    }

    private def handleMappingChange(
                mappings: IMap[UUID, PoolHealthMonitor]) {

        val convertedMap = convertDataMapToConfigMap(mappings, fileLocs, suffix)
        val loadBalancerIds = convertedMap.values.map(_.loadBalancerId).toSet
        for (loadBalancerId <- loadBalancerIds
             if !lbIdToRouterIdMap.contains(loadBalancerId)) {
            subscribe[SimLoadBalancer](loadBalancerId)
        }
        for (loadBalancerId <- lbIdToRouterIdMap.keySet
             if !loadBalancerIds.contains(loadBalancerId)) {
            unsubscribe[SimLoadBalancer](loadBalancerId)
        }

        val oldPoolSet = this.poolIdtoConfigMap.keySet
        val newPoolSet = convertedMap.keySet

        val added = newPoolSet -- oldPoolSet
        val deleted = oldPoolSet -- newPoolSet

        added.foreach(x => handleAddedMapping(x, mappings.get(x).orNull))
        deleted.foreach(handleDeletedMapping)
        // The config data might have been changed. Check for those.
        for (pool <- convertedMap.keySet) {
            (convertedMap.get(pool), this.poolIdtoConfigMap.get(pool)) match {
                case (Some(p), Some(o)) if p != null && o != null =>
                    if (!o.equals(p)) {
                        handleUpdatedMapping(pool, p)
                    }
                case _ =>
            }
        }

        this.poolIdtoConfigMap = convertedMap

        // clear out any load balancer mappings that aren't used anymore.
        // This seems gross and expensive, I know, but if we don't do it we
        // risk this map getting really big because we don't handle load
        // balancer deletes (not currently supported by
        // ClusterLoadBalancerManager).
        lbIdToRouterIdMap.keySet foreach {lbId =>
            val lbIdIsNotBeingUsed = poolIdtoConfigMap.values forall (conf =>
                    conf.loadBalancerId != lbId)
            if (lbIdIsNotBeingUsed)
                lbIdToRouterIdMap remove lbId
        }
    }

    override def receive = {

        case PoolHealthMonitorMap(mapping) =>
            log.debug("Received pool-healh monitor mappings: {}", mapping)
            handleMappingChange(mapping)

        case loadBalancer: SimLoadBalancer =>
            log.debug("Load balancer {} updated", loadBalancer.id)

            def notifyChangedRouter(loadBalancer: SimLoadBalancer,
                                    routerId: UUID) = {
                lbIdToRouterIdMap put (loadBalancer.id, routerId)
                // Notify every pool that is attached to this load balancer
                if (currentLeader) {
                    this.poolIdtoConfigMap filter
                        (kv => kv._2.loadBalancerId == loadBalancer.id) foreach
                        (kv => manager ! RouterChanged(kv._1, kv._2, routerId))
                }
            }
            val newRouterId = if (loadBalancer.adminStateUp)
                                  loadBalancer.routerId
                              else null

            lbIdToRouterIdMap get loadBalancer.id match {
                case Some(routerId) if routerId != newRouterId =>
                    notifyChangedRouter(loadBalancer, newRouterId)

                case None =>
                    notifyChangedRouter(loadBalancer, newRouterId)

                case _ =>
            }

        case BecomeHaproxyNode =>
            log.debug("{} has become the HM leader", self.path.name)
            currentLeader = true
            this.poolIdtoConfigMap foreach(kv =>
                manager ! ConfigAdded(kv._1, kv._2,
                    getRouterId(kv._2.loadBalancerId)))

        case OnCompleted => // ignore

        case OnError(e) => log.warn("Device error", e)

        case m => log.warn(s"Unknown message received: $m")
    }
}
