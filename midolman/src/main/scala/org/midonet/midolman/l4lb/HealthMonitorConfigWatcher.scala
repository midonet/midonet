/*
 * Copyright (c) 2014 Midokura Pte.Ltd.
 */
package org.midonet.midolman.l4lb

import akka.actor.{ActorRef, Props, Actor}
import java.util.UUID
import org.midonet.midolman.l4lb.PoolHealthMonitorMapManager.PoolHealthMonitorMap
import org.midonet.midolman.logging.ActorLogWithoutPath
import org.midonet.midolman.Referenceable
import org.midonet.midolman.simulation.LoadBalancer
import org.midonet.midolman.state.zkManagers.PoolHealthMonitorZkManager.PoolHealthMonitorConfig
import org.midonet.midolman.state.zkManagers.PoolHealthMonitorZkManager.PoolHealthMonitorConfig.{PoolMemberConfigWithId => ZkMemberConf, VipConfigWithId => ZkVipConf}
import org.midonet.midolman.topology.VirtualTopologyActor
import org.midonet.midolman.topology.VirtualTopologyActor.{LoadBalancerRequest, PoolHealthMonitorMapRequest}
import scala.collection.immutable.{Map => IMap}
import scala.collection.immutable.{Set => ISet}
import scala.collection.JavaConverters._
import scala.collection.mutable.HashMap
import scala.collection.mutable.{HashSet => MSet}
import scala.collection.mutable.{Map => MMap}
import scala.Predef._
import scala.Some


/**
 * This actor is responsible for providing the configuration data to
 * Health monitoring service.
 */
object HealthMonitorConfigWatcher {
    def props(fileLocs: String, suffix: String, manager: ActorRef): Props = {
        Props(new HealthMonitorConfigWatcher(fileLocs, suffix, manager))
    }

    def convertDataMapToConfigMap(
            map: IMap[UUID, PoolHealthMonitorConfig],
            fileLocs: String, suffix: String):
                IMap[UUID, PoolConfig] = {
        val newMap = HashMap[UUID, PoolConfig]()

        map foreach {case (id: UUID, phm: PoolHealthMonitorConfig) =>
                         newMap.put(id, convertDataToPoolConfig(id, fileLocs,
                             suffix, phm))}

        IMap(newMap.toSeq: _*)
    }

    val lbIdToRouterIdMap: MMap[UUID, UUID] = MMap.empty

    def getRouterId(loadBalancerId: UUID) =
        lbIdToRouterIdMap get loadBalancerId getOrElse null

    def convertDataToPoolConfig(poolId: UUID, fileLocs: String, suffix: String,
            data: PoolHealthMonitorConfig): PoolConfig = {
        if (data == null) {
            null
        } else if (data.loadBalancerConfig == null ||
            data.healthMonitorConfig == null ) {
            null
        } else {
            val vips = new MSet[VipConfig]
            for (vipConfig : ZkVipConf <- data.vipConfigs.asScala) {
                vips add new VipConfig(vipConfig.config.adminStateUp,
                                       vipConfig.persistedId,
                                       vipConfig.config.address,
                                       vipConfig.config.protocolPort,
                                       vipConfig.config.sessionPersistence)
            }
            val hm = new HealthMonitorConfig(
                    data.healthMonitorConfig.config.adminStateUp,
                    data.healthMonitorConfig.config.delay,
                    data.healthMonitorConfig.config.timeout,
                    data.healthMonitorConfig.config.maxRetries)
            val members = new MSet[PoolMemberConfig]()
            for (member: ZkMemberConf <- data.poolMemberConfigs.asScala) {
                members add new PoolMemberConfig(
                        member.config.adminStateUp,
                        member.persistedId,
                        member.config.weight,
                        member.config.address,
                        member.config.protocolPort)
            }
            new PoolConfig(poolId, data.loadBalancerConfig.persistedId,
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
        extends Referenceable with Actor with ActorLogWithoutPath {
    import HealthMonitor._
    import HealthMonitorConfigWatcher._
    import context._

    var poolIdtoConfigMap: IMap[UUID, PoolConfig] = IMap.empty

    var currentLeader: Boolean = false

    override val Name = "HealthMonitorConfigWatcher"

    override def preStart(): Unit = {
        VirtualTopologyActor.getRef() ! PoolHealthMonitorMapRequest(
                                            update = true)
    }

    private  def handleDeletedMapping(poolId: UUID) {
        log.debug("handleDeletedMapping deleting {}", poolId)
        if (currentLeader)
            manager ! ConfigDeleted(poolId)
    }

    private def handleAddedMapping(poolId: UUID,
                                   data: PoolHealthMonitorConfig) {
        log.debug("handleAddedMapping added {}", poolId)
        val poolConfig = convertDataToPoolConfig(poolId, fileLocs, suffix,
                                                 data)
        if (currentLeader) {
            manager ! ConfigAdded(poolId, poolConfig,
                getRouterId(poolConfig.loadBalancerId))
        }
    }

    private def handleUpdatedMapping(poolId: UUID, data: PoolConfig) {
        if (currentLeader)
            manager ! ConfigUpdated(poolId, data,
                getRouterId(data.loadBalancerId))
    }

    private def handleMappingChange(
                mappings: IMap[UUID, PoolHealthMonitorConfig]) {

        val convertedMap = convertDataMapToConfigMap(mappings, fileLocs, suffix)
        convertedMap.values filter (conf =>
            !lbIdToRouterIdMap.contains(conf.loadBalancerId)) foreach { conf =>
                VirtualTopologyActor.getRef() ! LoadBalancerRequest(
                        conf.loadBalancerId, update = true)
            }

        val oldPoolSet = this.poolIdtoConfigMap.keySet
        val newPoolSet = convertedMap.keySet

        val added = newPoolSet -- oldPoolSet
        val deleted = oldPoolSet -- newPoolSet

        added.foreach(x =>
                handleAddedMapping(x, mappings.get(x) getOrElse null))
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
            log.debug("{} mappings received", mapping.size)
            handleMappingChange(mapping)

        case loadBalancer: LoadBalancer =>
            log.debug("load balancer {} received", loadBalancer.id)

            def notifyChangedRouter(loadBalancer: LoadBalancer,
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
            currentLeader = true
            this.poolIdtoConfigMap foreach(kv =>
                manager ! ConfigAdded(kv._1, kv._2,
                    getRouterId(kv._2.loadBalancerId)))

        case m => log.error("unknown message received - {}", m)
    }
}
