/*
 * Copyright 2016 Midokura SARL
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
import java.util.concurrent.ExecutorService
import java.util.concurrent.atomic.AtomicBoolean

import scala.collection.immutable.{Map => IMap, Set => ISet}
import scala.collection.mutable
import scala.collection.mutable.{HashSet => MSet, Map => MMap}
import scala.collection.JavaConverters._
import scala.reflect._

import org.slf4j.LoggerFactory
import rx.Observable.OnSubscribe
import rx.schedulers.Schedulers
import rx.subjects.PublishSubject
import rx.{Scheduler, Observable, Subscriber}

import org.midonet.midolman.l4lb.HealthMonitor.HealthMonitorMessage
import org.midonet.midolman.simulation.{LoadBalancer => SimLoadBalancer, PoolMember => SimPoolMember, Vip => SimVip}
import org.midonet.midolman.state.l4lb.VipSessionPersistence
import org.midonet.midolman.topology.PoolHealthMonitorMapper.PoolHealthMonitorMapKey
import org.midonet.midolman.topology.VirtualTopology
import org.midonet.midolman.topology.VirtualTopology.{Device, Key}
import org.midonet.midolman.topology.devices.{PoolHealthMonitor, PoolHealthMonitorMap}
import org.midonet.util.functors._

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

    private val lbIdToRouterIdMap: MMap[UUID, UUID] = MMap.empty

    private def getRouterId(loadBalancerId: UUID) =
        lbIdToRouterIdMap.get(loadBalancerId).orNull

    def convertDataToPoolConfig(poolId: UUID, fileLocs: String,
                                suffix: String, data: PoolHealthMonitor)
    : PoolConfig = {
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

class HealthMonitorConfigWatcher(val fileLocs: String, val suffix: String,
                                 executor: ExecutorService)
    extends OnSubscribe[HealthMonitorMessage] {

    import HealthMonitor._
    import HealthMonitorConfigWatcher._

    private var poolIdtoConfigMap: IMap[UUID, PoolConfig] = IMap.empty
    protected var currentLeader: Boolean = false

    private val log = LoggerFactory.getLogger("org.midonet.hm-config-watcher")

    /* Configuration changes are published on this observable
       (ConfigAdded/ConfigUpdated/ConfigDeleted/RouterChanged). */
    private[l4lb] val hmConfigBus = PublishSubject.create[HealthMonitorMessage]()

    private val scheduler: Scheduler = Schedulers.from(executor)
    private val lbSubject = PublishSubject.create[Observable[SimLoadBalancer]]()
    private val subscribed = new AtomicBoolean(false)
    private val devices = new mutable.HashMap[Key, DeviceState[_]]

    protected class DeviceState[D <: Device](id: UUID)(implicit tag: ClassTag[D]) {
        private val mark = PublishSubject.create[D]()
        def observable: Observable[D] = VirtualTopology.observable[D](id)(tag)
                                                       .observeOn(scheduler)
                                                       .subscribeOn(scheduler)
                                                       .takeUntil(mark)
        def complete(): Unit = mark.onCompleted()
    }

    private def stopConfigWatcher(): Unit =
        devices.values.foreach(_.complete())

    protected def phmObservable(): Observable[PoolHealthMonitorMap] = {
        val phmState = new DeviceState[PoolHealthMonitorMap](PoolHealthMonitorMapKey)
        val key = Key(scala.reflect.classTag[PoolHealthMonitorMap],
                      PoolHealthMonitorMapKey)
        devices.put(key, phmState)
        phmState.observable
    }

    override def call(child: Subscriber[_ >: HealthMonitorMessage]): Unit = {
        if (subscribed.compareAndSet(false, true)) {
            Observable.merge(Observable.merge(lbSubject), phmObservable())
                      .flatMap[HealthMonitorMessage](makeFunc1(handleUpdate))
                      .doOnUnsubscribe(makeAction0(stopConfigWatcher()))
                      .subscribe(hmConfigBus)
            hmConfigBus.subscribe(child)
        } else {
            throw new IllegalStateException(
                "At most one subscriber at a time is supported")
        }
    }

    private  def handleDeletedMapping(poolId: UUID)
    : Option[HealthMonitorMessage] = {
        log.debug("handleDeletedMapping deleting {}", poolId)

        if (currentLeader) {
            Some(ConfigDeleted(poolId))
        } else {
            None
        }
    }

    private def handleAddedMapping(poolId: UUID,
                                   data: PoolHealthMonitor)
    : Option[HealthMonitorMessage] = {
        log.debug("handleAddedMapping added {}", poolId)

        if (currentLeader) {
            val poolConfig = convertDataToPoolConfig(poolId, fileLocs, suffix,
                                                     data)
            Some(ConfigAdded(poolId, poolConfig,
                             getRouterId(poolConfig.loadBalancerId)))
        } else {
            None
        }
    }

    private def handleUpdatedMapping(poolId: UUID, data: PoolConfig)
    : Option[HealthMonitorMessage] = {
        if (currentLeader) {
            Some(ConfigUpdated(poolId, data, getRouterId(data.loadBalancerId)))
        } else {
            None
        }
    }

    private def handleMappingChange(mappings: IMap[UUID, PoolHealthMonitor])
    : Observable[HealthMonitorMessage] = {
        val msgs = new mutable.ListBuffer[HealthMonitorMessage]()

        val convertedMap = convertDataMapToConfigMap(mappings, fileLocs, suffix)
        val loadBalancerIds = convertedMap.values.map(_.loadBalancerId).toSet
        for (loadBalancerId <- loadBalancerIds
             if !lbIdToRouterIdMap.contains(loadBalancerId)) {
            val lbState = new DeviceState[SimLoadBalancer](loadBalancerId)
            devices.put(Key(classTag[SimLoadBalancer], loadBalancerId), lbState)
            lbSubject onNext lbState.observable
        }
        for (loadBalancerId <- lbIdToRouterIdMap.keySet
             if !loadBalancerIds.contains(loadBalancerId)) {
            devices.remove(Key(classTag[SimLoadBalancer], loadBalancerId))
                   .foreach(_.complete())
        }

        val oldPoolSet = poolIdtoConfigMap.keySet
        val newPoolSet = convertedMap.keySet

        val added = newPoolSet -- oldPoolSet
        val deleted = oldPoolSet -- newPoolSet

        val addedMsgs =
            added.map(id => handleAddedMapping(id, mappings.get(id).orNull))
        val deletedMsgs = deleted.map(id => handleDeletedMapping(id))
        msgs ++= (addedMsgs ++ deletedMsgs).filter(_.isDefined).map(_.get)

        // The config data might have been changed. Check for those.
        for (pool <- convertedMap.keySet) {
            (convertedMap.get(pool), poolIdtoConfigMap.get(pool)) match {
                case (Some(p), Some(o)) if p != null && o != null =>
                    if (!o.equals(p)) {
                        handleUpdatedMapping(pool, p).foreach(msgs += _)
                    }
                case _ =>
            }
        }

        poolIdtoConfigMap = convertedMap

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
        Observable.from(msgs.toSeq.asJava)
    }

    protected def handleUpdate(update: Device)
    : Observable[HealthMonitorMessage] = update match {

        case PoolHealthMonitorMap(mapping) =>
            log.debug(s"${mapping.size} mappings received")
            handleMappingChange(mapping)

        case loadBalancer: SimLoadBalancer =>
            log.debug("Load balancer {} received", loadBalancer.id)

            def notifyChangedRouter(loadBalancer: SimLoadBalancer,
                                    routerId: UUID)
            : Observable[HealthMonitorMessage] = {
                lbIdToRouterIdMap put (loadBalancer.id, routerId)
                // Notify every pool that is attached to this load balancer
                if (currentLeader) {
                    val msgs = poolIdtoConfigMap filter
                        (kv => kv._2.loadBalancerId == loadBalancer.id) map
                        (kv => RouterChanged(kv._1, kv._2, routerId))
                    Observable.from(msgs.toSeq.asJava)
                } else {
                    Observable.empty()
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

                case _ => Observable.empty()
            }

        case x =>
            log.warn(s"Unknown update received: $x")
            Observable.empty()
    }

    def becomeHaproxyNode(): Unit = executor.submit(makeRunnable {
        log.debug("HAProxy health monitor is now leader")
        currentLeader = true
        poolIdtoConfigMap foreach(kv =>
            hmConfigBus onNext ConfigAdded(kv._1, kv._2,
                                           getRouterId(kv._2.loadBalancerId)))
    })
}
