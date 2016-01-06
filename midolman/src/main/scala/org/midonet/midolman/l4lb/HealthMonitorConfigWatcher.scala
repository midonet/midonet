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
import java.util.concurrent.ConcurrentHashMap

import scala.collection.immutable.{Map => IMap, Set => ISet}
import scala.collection.mutable
import scala.collection.mutable.{HashSet => MSet, Map => MMap}
import scala.reflect.ClassTag

import org.slf4j.LoggerFactory
import rx.Observable.OnSubscribe
import rx.subjects.PublishSubject
import rx.{Observer, Subscriber, Subscription}

import org.midonet.midolman.l4lb.HealthMonitor.HealthMonitorMessage
import org.midonet.midolman.simulation.{LoadBalancer => SimLoadBalancer, PoolMember => SimPoolMember, Vip => SimVip}
import org.midonet.midolman.state.l4lb.VipSessionPersistence
import org.midonet.midolman.topology.PoolHealthMonitorMapper.PoolHealthMonitorMapKey
import org.midonet.midolman.topology.VirtualTopology
import org.midonet.midolman.topology.VirtualTopology.{Device, Key}
import org.midonet.midolman.topology.devices.{PoolHealthMonitor, PoolHealthMonitorMap}

/**
  * This class is responsible for providing the configuration data to the
  * Health monitor.
  */
object HealthMonitorConfigWatcher {

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
}

class HealthMonitorConfigWatcher(val fileLocs: String,
                                 val suffix: String)
    extends OnSubscribe[HealthMonitorMessage] {

    import HealthMonitor._
    import HealthMonitorConfigWatcher._

    var poolIdtoConfigMap: IMap[UUID, PoolConfig] = IMap.empty

    var currentLeader: Boolean = false

    val Name = "HealthMonitorConfigWatcher"

    private val log =
        LoggerFactory.getLogger("org.midonet.healthmonitor-configwatcher")

    /* Configuration changes are published on this observable
       (ConfigAdded/ConfigUpdated/ConfigDeleted/RouterChanged). */
    private[l4lb] val observable = PublishSubject.create[HealthMonitorMessage]()

    private var subscribed = false

    override def call(child: Subscriber[_ >: HealthMonitorMessage]): Unit = {
        observable.subscribe(child)

        if (!subscribed) {
            subscribe[PoolHealthMonitorMap](PoolHealthMonitorMapKey)
            subscribed = true
        }
    }

    private val subscriptions = new ConcurrentHashMap[Key, Subscription]

    protected val observer = new Observer[AnyRef]() {
        override def onCompleted(): Unit =
            log.warn("A device update stream completed unexpectedly")

        override def onError(e: Throwable): Unit =
            log.warn("Device error", e)

        override def onNext(update: AnyRef): Unit = handleUpdate(update)
    }

    /**
      * Subscribes to notifications for the specified device.
      */
    protected def subscribe[D <: Device](id: UUID)(implicit tag: ClassTag[D])
    : Unit = {
        val key = Key(tag, id)
        subscriptions.get(key) match {
            case null =>
                subscriptions.put(key,
                                  VirtualTopology.observable[D](id)(tag)
                                      .subscribe(observer))
            case subscription if subscription.isUnsubscribed =>
                subscriptions.put(key,
                                  VirtualTopology.observable[D](id)(tag)
                                      .subscribe(observer))
            case _ =>
        }

    }

    /**
      * Unsubscribes from notifications from the specified device. The method
      * returns `true` if the actor was subscribed to the device, `false`
      * otherwise.
      */
    protected def unsubscribe[D <: Device](id: UUID)(implicit tag: ClassTag[D])
    : Boolean = {
        subscriptions.get(Key(tag, id)) match {
            case null => false
            case subscription =>
                subscription.unsubscribe()
                true
        }
    }

    private  def handleDeletedMapping(poolId: UUID) {
        log.debug("handleDeletedMapping deleting {}", poolId)
        if (currentLeader) {
            observable onNext ConfigDeleted(poolId)
        }
    }

    private def handleAddedMapping(poolId: UUID,
                                   data: PoolHealthMonitor) {
        log.debug("handleAddedMapping added {}", poolId)
        val poolConfig = convertDataToPoolConfig(poolId, fileLocs, suffix, data)
        if (currentLeader) {
            observable onNext ConfigAdded(poolId, poolConfig,
                                  getRouterId(poolConfig.loadBalancerId))
        }
    }

    private def handleUpdatedMapping(poolId: UUID, data: PoolConfig) {
        if (currentLeader) {
            observable onNext ConfigUpdated(poolId, data,
                                            getRouterId(data.loadBalancerId))
        }
    }

    private def handleMappingChange(mappings: IMap[UUID, PoolHealthMonitor]) {
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

    private def handleUpdate(update: AnyRef) = update match {

        case PoolHealthMonitorMap(mapping) =>
            log.debug(s"${mapping.size} mappings received")
            handleMappingChange(mapping)

        case loadBalancer: SimLoadBalancer =>
            log.debug("load balancer {} received", loadBalancer.id)

            def notifyChangedRouter(loadBalancer: SimLoadBalancer,
                                    routerId: UUID) = {
                lbIdToRouterIdMap put (loadBalancer.id, routerId)
                // Notify every pool that is attached to this load balancer
                if (currentLeader) {
                    this.poolIdtoConfigMap filter
                        (kv => kv._2.loadBalancerId == loadBalancer.id) foreach
                        (kv => observable onNext
                                   RouterChanged(kv._1, kv._2, routerId))
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

        case x => log.warn(s"Unknown update received: $x")
    }

    private[l4lb] def becomeHaproxyNode(): Unit = {
        log.debug("We have become the health monitor leader")
        currentLeader = true
        this.poolIdtoConfigMap foreach(kv =>
            observable onNext ConfigAdded(kv._1, kv._2,
                                          getRouterId(kv._2.loadBalancerId)))
    }
}
