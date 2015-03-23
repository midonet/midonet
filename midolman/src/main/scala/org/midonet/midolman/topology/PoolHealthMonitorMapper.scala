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

import rx.{Observer, Observable}
import rx.subjects.BehaviorSubject

import org.midonet.cluster.models.Topology.{Pool => ProtoPool}
import org.midonet.cluster.util.UUIDUtil
import org.midonet.midolman.simulation.Pool
import org.midonet.midolman.topology.devices.{PoolHealthMonitor, PoolHealthMonitorMap}
import org.midonet.util.functors.makeFunc1

object PoolHealthMonitorMapper {
    /**
     * Globally unique ID used to represent a map of health monitor and pool
     * mappings.  The reason why we need this is because VTA and ClusterManager
     * requires that there is a key to retrieve data.
     */
    final val POOL_HEALTH_MONITOR_MAP_KEY =
        UUID.fromString("f7c96553-a9c6-48b7-933c-31563bf77952");
}

/**
 * A device mapper that exposes an observable with change notifications
 * for mappings between pools and their associated pool health monitors.
 */
class PoolHealthMonitorMapper(vt: VirtualTopology)
    extends DeviceMapper[PoolHealthMonitorMap](
        PoolHealthMonitorMapper.POOL_HEALTH_MONITOR_MAP_KEY, vt) {

    override def logSource = "org.midonet.devices.poolhealthmonitormap"

    // The map from pool id to health monitor info that we emit via
    // the observable
    private var mappings: Map[UUID, PoolHealthMonitor] = Map()

    // The pools observable provides a stream of pool simulation objects
    // obtained from the pool mapper. The generation goes as follows:
    //
    //              store.observable(classOf[Protocol Port])
    //                              |
    //                              V
    //                     Obs[Obs[Protocol Pool]]
    //                              | (map + merge)
    //                              V
    //                           Obs[Id] (existing + newly created pool ids)
    //                              | (map)
    //                              V
    //                      Obs[Obs[Sim Pool]]
    //
    private case class PoolObservable(id: UUID, obs: Observable[Pool])
    private lazy val poolsObservable: Observable[PoolObservable] =
        Observable.merge(
            vt.store.observable(classOf[ProtoPool]).map(makeFunc1(obs => {
                obs.take(1).map(makeFunc1(u => {UUIDUtil.fromProto(u.getId)}))
            }))
        ).map(makeFunc1(id => {
            PoolObservable(id, VirtualTopology.observable[Pool](id)
                .onErrorResumeNext(Observable.empty)
                .observeOn(vt.scheduler))
        })).observeOn(vt.scheduler)

    poolsObservable.subscribe(new Observer[PoolObservable]() {
        override def onError(e: Throwable): Unit = {
            log.error("pool observable error", e)
        }
        override def onCompleted(): Unit = {
            log.warn("pool observable closed")
        }
        override def onNext(p: PoolObservable): Unit = {
            p.obs.subscribe(new Observer[Pool]() {
                override def onError(e: Throwable): Unit = {}
                override def onCompleted(): Unit = poolDeleted(p.id)
                override def onNext(pool: Pool): Unit = poolUpdated(pool)
            })
        }
    })

    // Process pool updates
    private def poolUpdated(pool: Pool) = {
        val newMembers = pool.activePoolMembers.toIterable ++
            pool.disabledPoolMembers.toIterable
        val pmh = mappings.get(pool.id) match {
            case Some(PoolHealthMonitor(hm, lb, vips, _)) =>
                PoolHealthMonitor(hm, lb, vips, newMembers)
            case None =>
                PoolHealthMonitor(null, null, null, newMembers)
        }
        mappings = mappings updated (pool.id, pmh)
        subject.onNext(mappings)
    }

    // Process pool deletion
    private def poolDeleted(poolId: UUID) = {
        mappings = mappings - poolId
        subject.onNext(mappings)
    }


    // Build the health monitor map from completelly filled-in entries
    private def buildPoolHealthMonitorMap(m: Map[UUID, PoolHealthMonitor]) = {
        PoolHealthMonitorMap(m.filter(e => {
            e._2.healthMonitor != null &&
            e._2.loadBalancer != null &&
            e._2.vips != null &&
            e._2.poolMembers != null
        }))
    }

    // The output device observable for the pool health monitor mapper
    protected lazy val subject = BehaviorSubject.create(mappings)
    protected override lazy val observable: Observable[PoolHealthMonitorMap] =
        subject.asObservable()
            .map(makeFunc1(buildPoolHealthMonitorMap))
            .distinctUntilChanged()
}
