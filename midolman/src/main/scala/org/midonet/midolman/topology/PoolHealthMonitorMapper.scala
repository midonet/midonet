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
import rx.Observable
import rx.subjects.{BehaviorSubject, PublishSubject}

import org.midonet.cluster.models.{Topology => Proto}
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
 *
 * This mapper monitors all pools, and maps them to health monitor information.
 * The associated information consist of:
 * - HealthMonitor parameters: obtained from Zoom by monitoring the
 *     HealthMonitor topology objects
 * - LoadBalancer: obtained from LoadBalancerMapper from VirtualTopology; it
 *     includes the list of vips
 * - Vips: the list of vips from the LoadBalancer above that are associated
 *     to the given pool
 * - Pool members: obtained from the PoolMapper from VirtualTopology; it
 *     includes both active and disabled members
 *
 * Mappings for pools with incomplete or invalid health monitor or load
 * balancer ids are filtered out.
 *
 * This mapper assumes the following relations:
 *   LoadBalancer (1 <-> *) VIP (1 <-> 1) Pool (1 <-> *) PoolMember
 */
class PoolHealthMonitorMapper(vt: VirtualTopology)
    extends DeviceMapper[PoolHealthMonitorMap](
        PoolHealthMonitorMapper.PoolHealthMonitorMapKey, vt) {

    override def logSource = "org.midonet.devices.poolhealthmonitor"

    private case class
    PoolHealthMonitorEntry(poolId: UUID, poolHealthMonitor: PoolHealthMonitor)

    /**
     * Stores the state for a health monitor
     */
    private final class HealthMonitorState(val healthMonitorId: UUID) {
        private var currentHealthMonitor: HealthMonitor = null
        private val mark = PublishSubject.create[HealthMonitor]()

        private val updateHealthMonitor =
            makeFunc1[Proto.HealthMonitor, HealthMonitor](p => {
                currentHealthMonitor =
                    ZoomConvert.fromProto(p, classOf[HealthMonitor])
                currentHealthMonitor
            })

        private val completeHealthMonitor =
            makeAction0(currentHealthMonitor = null)

        /** The health monitor observable */
        val observable: Observable[HealthMonitor] =
            if (healthMonitorId == null) Observable.empty()
            else vt.store
            .observable(classOf[Proto.HealthMonitor], healthMonitorId)
            .observeOn(vt.scheduler)
            .map[HealthMonitor](updateHealthMonitor)
            .takeUntil(mark)
            .doOnCompleted(completeHealthMonitor)

        /** Completes the observable corresponding to this health monitor */
        def complete() = mark.onCompleted()
        /** Gets the current health monitor or null, if none is set */
        @Nullable def healthMonitor: HealthMonitor = currentHealthMonitor
    }

    /**
     * Stores the state of a load balancer
     */
    private final class LoadBalancerState(val loadBalancerId: UUID) {
        private var currentLoadBalancer: LoadBalancer = null
        private val mark = PublishSubject.create[LoadBalancer]()

        private val updateLoadBalancer =
            makeAction1[LoadBalancer](currentLoadBalancer = _)

        private val completeLoadBalancer =
            makeAction0(currentLoadBalancer = null)

        /** The load balancer observable */
        val observable: Observable[LoadBalancer] =
            if (loadBalancerId == null) Observable.empty()
            else VirtualTopology.observable[LoadBalancer](loadBalancerId)
            .onErrorResumeNext(Observable.empty)
            .observeOn(vt.scheduler)
            .doOnNext(updateLoadBalancer)
            .takeUntil(mark)
            .doOnCompleted(completeLoadBalancer)

        /** Completes the observable corresponding to this load balancer */
        def complete() = mark.onCompleted()
        /** Gets the current load balancer or null, if none is set */
        @Nullable def loadBalancer: LoadBalancer = currentLoadBalancer
        /** Get the vips associated to the current load balancer, or null */
        @Nullable def vipsByPool(poolId: UUID): Iterable[VIP] =
            if (currentLoadBalancer == null) null
            else currentLoadBalancer.vips.filter(_.poolId == poolId)
    }

    /**
     * Stores the aggregate state for a pool health monitor
     */
    private final class PoolHealthMonitorState(val poolId: UUID) {
        private var pool: Pool = null
        private var members: Iterable[PoolMember] = null
        private var hMon: HealthMonitorState = new HealthMonitorState(null)
        private var lBal: LoadBalancerState = new LoadBalancerState(null)
        private val mark = PublishSubject.create[PoolHealthMonitorEntry]()

        /** A collector for all related updates */
        private val funnel = PublishSubject.create[Observable[_ <: Any]]()

        /** Build a partial object upon update reception */
        private val deviceUpdated = makeFunc1[Any, PoolHealthMonitorEntry] {
            info => {
                info match {
                    case p: Pool if pool == null =>
                        log.debug("pool received: " + poolId)
                        hMon = new HealthMonitorState(p.healthMonitorId)
                        funnel.onNext(hMon.observable)
                        lBal = new LoadBalancerState(p.loadBalancerId)
                        funnel.onNext(lBal.observable)
                        members = p.activePoolMembers ++ p.disabledPoolMembers
                        pool = p
                    case p: Pool =>
                        log.debug("pool updated: " + poolId)
                        if (p.healthMonitorId != hMon.healthMonitorId) {
                            hMon.complete()
                            hMon = new HealthMonitorState(p.healthMonitorId)
                            funnel.onNext(hMon.observable)
                        }
                        if (p.loadBalancerId != lBal.loadBalancerId) {
                            lBal.complete()
                            lBal = new LoadBalancerState(p.loadBalancerId)
                            funnel.onNext(lBal.observable)
                        }
                        members = p.activePoolMembers ++ p.disabledPoolMembers
                        pool = p
                    case hm: HealthMonitor =>
                        log.debug("updated health monitor for pool: " + poolId)
                    case lb: LoadBalancer =>
                        log.debug("updated load balancer for pool: " + poolId)
                    case other =>
                        log.debug("unexpected update for pool: " + poolId +
                                  ": " + other)
            }
            PoolHealthMonitorEntry(poolId, PoolHealthMonitor(
                hMon.healthMonitor, lBal.loadBalancer, lBal.vipsByPool(poolId),
                members))
        }}

        private val completion = makeAction0 {
            log.debug("pool terminated: " + poolId)
            hMon.complete()
            lBal.complete()
            mark.onCompleted()
            funnel.onCompleted()
        }
        /** An observable for pool health monitor updates */
        lazy val observable =
            Observable.merge[Any](
                Observable.merge[Any](funnel),
                VirtualTopology.observable[Pool](poolId)
                    .onErrorResumeNext(Observable.empty())
                    .observeOn(vt.scheduler)
                    .doOnCompleted(completion)
            )
            .observeOn(vt.scheduler)
            .map[PoolHealthMonitorEntry](deviceUpdated)
            .distinctUntilChanged()
            .takeUntil(mark)
    }

    // The map from pool id to health monitor info that we use to generate
    // the observable
    private var mappings: Map[UUID, PoolHealthMonitor] = Map()

    /** A subject to merge updates from different sources */
    private val updateSubject =
        PublishSubject.create[Observable[PoolHealthMonitorEntry]]()

    /** A subject to keep track of existing and new pool ids */
    private lazy val poolIdObservable = Observable.merge[UUID](
        vt.store.observable(classOf[Proto.Pool])
            .map[Observable[UUID]](makeFunc1(obs => {
            obs.take(1).map[UUID](makeFunc1(p => {fromProto(p.getId)}))
        }))
    )

    /** Process additions, updates and removals from mappings */
    private val processUpdate = makeFunc1[Any, PoolHealthMonitorMap] (
        obj => {obj match {
            case poolId: UUID =>
                updateSubject.onNext(
                    new PoolHealthMonitorState(poolId).observable
                        .doOnCompleted(makeAction0 {updateSubject.onNext(
                            Observable.just(PoolHealthMonitorEntry(poolId, null))
                        )}))
            case PoolHealthMonitorEntry(poolId, null) =>
                mappings = mappings - poolId
            case PoolHealthMonitorEntry(poolId, poolHealthMonitor) =>
                mappings = mappings updated(poolId, poolHealthMonitor)

        }
        PoolHealthMonitorMap(mappings.filter(e => {
            e._2.healthMonitor != null &&
            e._2.loadBalancer != null &&
            e._2.vips != null &&
            e._2.poolMembers != null
        }))
    })

    private val subject =
        BehaviorSubject.create[PoolHealthMonitorMap](PoolHealthMonitorMap(Map()))
    private val dataSubscription =
        Observable.merge[Any](
            Observable.merge[PoolHealthMonitorEntry](updateSubject),
            poolIdObservable)
            .observeOn(vt.scheduler)
            .subscribeOn(vt.scheduler)
            .map[PoolHealthMonitorMap](processUpdate)
            .subscribe(subject)
    protected override val observable =
        subject.asObservable().distinctUntilChanged()
}
