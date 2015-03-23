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

package org.midonet.midolman.topology

import java.util.UUID
import javax.annotation.Nullable

import org.midonet.cluster.data.ZoomConvert
import rx.{Observer, Observable}
import rx.subjects.{PublishSubject, BehaviorSubject}

import org.midonet.cluster.models.Topology.{Pool => ProtoPool, HealthMonitor => ProtoHealthMonitor}
import org.midonet.cluster.util.UUIDUtil.fromProto
import org.midonet.midolman.simulation.{VIP, LoadBalancer, PoolMember, Pool}
import org.midonet.midolman.topology.devices.{HealthMonitor, PoolHealthMonitor, PoolHealthMonitorMap}
import org.midonet.util.functors.{makeAction0, makeAction1, makeFunc1}

object PoolHealthMonitorMapper {
    /**
     * Globally unique ID used to represent a map of health monitor and pool
     * mappings.  The reason why we need this is because VTA and ClusterManager
     * requires that there is a key to retrieve data.
     */
    final val PoolHealthMonitorMapKey =
        UUID.fromString("f7c96553-a9c6-48b7-933c-31563bf77952")
}

/**
 * A device mapper that exposes an observable with change notifications
 * for mappings between pools and their associated pool health monitors.
 */
class PoolHealthMonitorMapper(vt: VirtualTopology)
    extends DeviceMapper[PoolHealthMonitorMap](
        PoolHealthMonitorMapper.PoolHealthMonitorMapKey, vt) {

    override def logSource = "org.midonet.devices.poolhealthmonitor"

    /**
     * Stores the state for a health monitor
     */
    private final class HealthMonitorState(val healthMonitorId: UUID) {
        private var currentHealthMonitor: HealthMonitor = null
        private val mark = PublishSubject.create[HealthMonitor]()

        /** The health monitor observable */
        val observable = vt.store
            .observable(classOf[ProtoHealthMonitor], healthMonitorId)
            .observeOn(vt.scheduler)
            .doOnNext(makeAction1(p => {
                currentHealthMonitor =
                    ZoomConvert.fromProto(p, classOf[HealthMonitor])}))
            .takeUntil(mark)
            .doOnCompleted(makeAction0 {currentHealthMonitor = null})

        /** Completes the observable corresponding to this health monitor */
        def complete() = mark.onCompleted()
        /** Gets the current health monitor or null, if none is set */
        @Nullable def healthMonitor: HealthMonitor = currentHealthMonitor
        /** Indicate if health monitor data is available */
        def isReady: Boolean = currentHealthMonitor ne null
    }

    /**
     * Stores the state of a load balancer
     */
    private final class LoadBalancerState(val lbId: UUID) {
        private var currentLoadBalancer: LoadBalancer = null
        private val mark = PublishSubject.create[LoadBalancer]()

        /** The load balancer observable */
        val observable = VirtualTopology.observable[LoadBalancer](lbId)
            .onErrorResumeNext(Observable.empty)
            .observeOn(vt.scheduler)
            .doOnNext(makeAction1(currentLoadBalancer = _))
            .takeUntil(mark)

        /** Completes the observable corresponding to this load balancer */
        def complete() = mark.onCompleted()
        /** Gets the current load balancer or null, if none is set */
        @Nullable def loadBalancer: LoadBalancer = currentLoadBalancer
        /** Gets the current vips for this load balancer  */
        @Nullable def vips: Iterable[VIP] =
            if (loadBalancer == null) null else loadBalancer.vips
        /** Indicate if health monitor data is available */
        def isReady: Boolean = currentLoadBalancer ne null
    }

    /**
     * Stores the state for a pool
     */
    private final class PoolState(val poolId: UUID) {
        private var currentPool: Pool = null
        private val mark = PublishSubject.create[Pool]()

        /** The pools observable */
        val observable = VirtualTopology.observable[Pool](poolId)
            .onErrorResumeNext(Observable.empty)
            .observeOn(vt.scheduler)
            .doOnNext(makeAction1(currentPool = _))
            .takeUntil(mark)

        /** Completes the observable corresponding to this health monitor */
        def complete() = mark.onCompleted()
        /** Gets the current health monitor or null, if none is set */
        @Nullable def pool: Pool = currentPool
        /** Gets the current pool members  */
        @Nullable def poolMembers: Iterable[PoolMember] =
            if (currentPool == null) null
            else currentPool.activePoolMembers ++ currentPool.disabledPoolMembers
        /** Indicate if health monitor data is available */
        def isReady: Boolean = currentPool ne null
    }

    /**
     * Stores the aggregate state for a pool health monitor
     */
    private final class PoolHealthMonitorState(val poolId: UUID) {
        private val pool: PoolState = new PoolState(poolId)
        private var hMon: HealthMonitorState = null
        private var lBal: LoadBalancerState = null
        private var vips: Set[VIP] = Set()

        /** A collector for all related updates */
        private val funnel = PublishSubject.create[Observable[_ <: Any]]()
        private val mark = PublishSubject.create[Any]()

        /** Build a partial object upon update reception */
        private def deviceUpdated(info: Any): PoolHealthMonitor = {
            info match {
                case p: Pool =>
                    if (hMon == null && p.healthMonitorId != null) {
                        hMon = new HealthMonitorState(p.healthMonitorId)
                        funnel.onNext(hMon.observable)
                    } else if (hMon != null &&
                        hMon.healthMonitorId != p.healthMonitorId) {
                        hMon.complete()
                        if (p.healthMonitorId == null)
                            hMon = null
                        else {
                            hMon = new HealthMonitorState(p.healthMonitorId)
                            funnel.onNext(hMon.observable)
                        }
                    }
                    if (lBal == null && p.loadBalancerId != null) {
                        lBal = new LoadBalancerState(p.loadBalancerId)
                        funnel.onNext(lBal.observable)
                    } else if (lBal != null && lBal.lbId != p.loadBalancerId) {
                        vips = vips.filterNot(_.loadBalancerId == lBal.lbId)
                        lBal.complete()
                        if (p.loadBalancerId == null)
                            lBal = null
                        else {
                            lBal = new LoadBalancerState(p.loadBalancerId)
                            funnel.onNext(lBal.observable)
                        }
                    }
                case b: LoadBalancer =>
                    vips = vips.filterNot(_.loadBalancerId == b.id) ++
                        b.vips.filter(_.poolId == poolId).toSet
                case m: HealthMonitor =>
                case other =>
            }
            PoolHealthMonitor(if (hMon == null) null else hMon.healthMonitor,
                              if (lBal == null) null else lBal.loadBalancer,
                              vips, pool.poolMembers)
        }

        /** An observable for pool health monitor updates */
        val observable: Observable[PoolHealthMonitor] =
            Observable.merge[Any](funnel)
                .observeOn(vt.scheduler)
                .map[PoolHealthMonitor](makeFunc1(deviceUpdated))
                .takeUntil(mark)
                .doOnSubscribe(makeAction0 {funnel.onNext(
                    pool.observable.doOnCompleted(makeAction0 {complete()}))
                })

        /** Completes the observable for this pool health monitor */
        def complete() = mark.onCompleted()
    }

    // The map from pool id to health monitor info that we use to generate
    // the observable
    private var mappings: Map[UUID, PoolHealthMonitor] = Map()
    private val subject = BehaviorSubject.create(mappings)

    // Add mapping for a specific pool
    private def addPool(poolId: UUID): Unit = {
        val state = new PoolHealthMonitorState(poolId)
        state.observable.observeOn(vt.scheduler)
            .subscribe(new Observer[PoolHealthMonitor] {
            override def onError(e: Throwable): Unit = {}
            override def onCompleted(): Unit = {
                mappings = mappings - poolId
                subject.onNext(mappings)
            }
            override def onNext(phm: PoolHealthMonitor): Unit = {
                mappings = mappings updated (poolId, phm)
                subject.onNext(mappings)
            }
        })
    }

    // Gather mappings for existing pools (and watch newly created ones)
    private val poolIdSubscription = Observable.merge(
        // Observable[Observable[Topology.Pool]]
        vt.store.observable(classOf[ProtoPool])
            // -> Observable[UUID]
            .map[Observable[UUID]](makeFunc1(obs => {
                obs.take(1).map[UUID](makeFunc1(u => {fromProto(u.getId)}))
        }))).observeOn(vt.scheduler).subscribe(makeAction1(addPool))


    // Build the health monitor map from completely filled-in entries
    private def buildPoolHealthMonitorMap(m: Map[UUID, PoolHealthMonitor]) = {
        PoolHealthMonitorMap(m.filter(e => {
            e._2.healthMonitor != null &&
            e._2.loadBalancer != null &&
            e._2.vips != null &&
            e._2.poolMembers != null
        }))
    }

    // The output device observable for the pool health monitor mapper
    protected override lazy val observable: Observable[PoolHealthMonitorMap] =
        subject.asObservable()
            .map[PoolHealthMonitorMap](makeFunc1(buildPoolHealthMonitorMap))
            .distinctUntilChanged()
}
