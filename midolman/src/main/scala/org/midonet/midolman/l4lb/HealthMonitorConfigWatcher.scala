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
import org.midonet.midolman.state.zkManagers.PoolZkManager.PoolHealthMonitorMappingConfig
import org.midonet.midolman.state.zkManagers.PoolZkManager.PoolHealthMonitorMappingConfig.{PoolMemberConfigWithId => ZkMemberConf}
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
            map: MMap[UUID, PoolHealthMonitorMappingConfig],
            fileLocs: String, suffix: String):
                IMap[UUID, PoolConfig] = {
        val newMap = HashMap[UUID, PoolConfig]()

        map foreach {case (id: UUID, phm: PoolHealthMonitorMappingConfig) =>
                         newMap.put(id, convertDataToPoolConfig(id, fileLocs,
                             suffix, phm))}

        IMap(newMap.toSeq: _*)
    }

    val lbIdToRouterIdMap: MMap[UUID, UUID] = MMap.empty

    def getRouterId(loadBalancerId: UUID) =
        lbIdToRouterIdMap get loadBalancerId getOrElse null

    def convertDataToPoolConfig(poolId: UUID, fileLocs: String, suffix: String,
            data: PoolHealthMonitorMappingConfig): PoolConfig = {
        if (data == null) {
            null
        } else if (data.loadBalancerConfig == null ||
            data.healthMonitorConfig == null ) {
            null
        } else {
            val lbId = data.loadBalancerConfig.persistedId
            var vip: VipConfig = null
            if (!data.vipConfigs.isEmpty) {
                // the data comes with a list of configs, but we currently
                // only support one.
                vip = new VipConfig(data.loadBalancerConfig.persistedId,
                                    data.vipConfigs.get(0).config.address,
                                    data.vipConfigs.get(0).config.protocolPort)
            }
            val hm = new HealthMonitorConfig(
                                   data.healthMonitorConfig.config.delay,
                                   data.healthMonitorConfig.config.timeout,
                                   data.healthMonitorConfig.config.maxRetries)
            val members = new MSet[PoolMemberConfig]()
            for (member: ZkMemberConf <- data.poolMemberConfigs.asScala) {
                members add new PoolMemberConfig(member.persistedId,
                member.config.address,
                member.config.protocolPort)
            }
            new PoolConfig(poolId, data.loadBalancerConfig.persistedId, vip,
                    ISet(members.toSeq:_*), hm,
                    data.loadBalancerConfig.config.adminStateUp, fileLocs,
                    suffix)
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

    var currentLeader: Boolean = false

    var poolIdtoConfigMap: IMap[UUID, PoolConfig] = IMap.empty

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
                                   data: PoolHealthMonitorMappingConfig) {
        log.debug("handleAddedMapping added {}", poolId)
        val poolConfig = convertDataToPoolConfig(poolId, fileLocs, suffix,
                                                 data)
        if (currentLeader) {
            manager ! ConfigAdded(poolId, poolConfig,
                getRouterId(poolConfig.loadBalancerId))
        }
    }

    private def handleUpdatedMapping(poolId: UUID, data: PoolConfig) {
        if (data.isConfigurable && currentLeader)
            manager ! ConfigUpdated(poolId, data,
                getRouterId(data.loadBalancerId))
    }

    private def handleMappingChange(
                mappings: MMap[UUID, PoolHealthMonitorMappingConfig]) {

        // remove all configs that aren't configurable -- treat them as
        // deleted.
        val convertedMap = convertDataMapToConfigMap(mappings, fileLocs, suffix)
        convertedMap.values filter (conf =>
            !lbIdToRouterIdMap.contains(conf.loadBalancerId)) foreach { conf =>
                VirtualTopologyActor.getRef() ! LoadBalancerRequest(
                        conf.loadBalancerId, update = true)
            }

        val newMapping =
            IMap((convertedMap filterKeys (id =>
                      convertedMap(id).isConfigurable)).toList: _ *)

        val oldPoolSet = this.poolIdtoConfigMap.keySet
        val newPoolSet = newMapping.keySet

        val added = newPoolSet -- oldPoolSet
        val deleted = oldPoolSet -- newPoolSet

        added.foreach(x =>
                handleAddedMapping(x, mappings.get(x) getOrElse(null)))
        deleted.foreach(handleDeletedMapping)

        // The config data might have been changed. Check for those.
        for (pool <- newMapping.keySet) {
            (newMapping.get(pool), this.poolIdtoConfigMap.get(pool)) match {
                case (Some(p), Some(o)) =>
                    if (!o.equals(p)) {
                        handleUpdatedMapping(pool, p)
                    }
                case _ =>
            }
        }

        this.poolIdtoConfigMap = newMapping

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
                this.poolIdtoConfigMap filter
                    (kv => kv._2.loadBalancerId == loadBalancer.id) foreach
                    (kv => manager ! RouterChanged(kv._1, kv._2, routerId))
            }

            lbIdToRouterIdMap get loadBalancer.id match {
                case Some(routerId) if routerId != loadBalancer.routerId =>
                    notifyChangedRouter(loadBalancer, loadBalancer.routerId)

                case None =>
                    notifyChangedRouter(loadBalancer, loadBalancer.routerId)

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
