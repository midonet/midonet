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

import java.lang.{Boolean => JBoolean}
import java.util.UUID

import scala.collection.JavaConverters._
import scala.collection.mutable

import rx.Observable
import rx.subjects.PublishSubject

import org.midonet.cluster.data.ZoomConvert
import org.midonet.cluster.models.Topology.{Pool => TopologyPool, PoolMember => TopologyPoolMember}
import org.midonet.cluster.util.UUIDUtil._
import org.midonet.midolman.simulation.{Pool => SimulationPool, PoolMember => SimulationPoolMember}
import org.midonet.midolman.state.l4lb.PoolLBMethod
import org.midonet.midolman.topology.PoolMapper.PoolMemberState
import org.midonet.util.functors.{makeAction0, makeAction1, makeFunc1}

object PoolMapper {

    /**
     * Stores the state for a pool member, and exposes an [[rx.Observable]] that
     * emits updates for this pool member. This observable completes either when
     * the pool member is deleted, or when calling the complete() method, which
     * is used to signal that a member no longer belongs to a pool.
     */
    private class PoolMemberState(val poolMemberId: UUID, vt: VirtualTopology) {

        private var currentMember: SimulationPoolMember = null
        private val mark = PublishSubject.create[SimulationPoolMember]

        val observable = vt.store
            .observable(classOf[TopologyPoolMember], poolMemberId)
            .distinctUntilChanged()
            .observeOn(vt.scheduler)
            .map[SimulationPoolMember](makeFunc1(poolMemberUpdated))
            .takeUntil(mark)

        /** Completes the observable corresponding to the pool member state */
        def complete(): Unit = mark.onCompleted()
        /** Gets the underlying pool member of this pool member state */
        def poolMember: SimulationPoolMember = currentMember
        /** Indicates that the pool member state has received the member data */
        def isReady: Boolean = currentMember ne null

        private def poolMemberUpdated(poolMember: TopologyPoolMember)
        : SimulationPoolMember = {
            currentMember =
                ZoomConvert.fromProto(poolMember, classOf[SimulationPoolMember])
            currentMember
        }
    }
}

/**
 * A device mapper that exposes an [[rx.Observable]] with notifications for a
 * layer-4 load balancer pool.
 */
final class PoolMapper(poolId: UUID, vt: VirtualTopology)
    extends VirtualDeviceMapper[SimulationPool](poolId, vt) {

    override def logSource = s"org.midonet.devices.pool.pool-$poolId"

    private var pool: TopologyPool = null
    private val poolMembers = new mutable.HashMap[UUID, PoolMemberState]

    // A subject that emits a pool member observable for every pool member added
    // to the pool.
    private lazy val poolMembersSubject = PublishSubject
        .create[Observable[SimulationPoolMember]]
    private lazy val poolMembersObservable = Observable
        .merge(poolMembersSubject)
        .map[TopologyPool](makeFunc1(poolMemberUpdated))
    private lazy val poolObservable = vt.store
        .observable(classOf[TopologyPool], poolId)
        .distinctUntilChanged()
        .observeOn(vt.scheduler)
        .doOnCompleted(makeAction0(poolDeleted()))
        .doOnNext(makeAction1(poolUpdated))

    protected override val observable = Observable
        .merge[Any](poolMembersObservable, poolObservable)
        .filter(makeFunc1(isPoolReady))
        .map[SimulationPool](makeFunc1(buildDevice))

    /**
     * Indicates that the pool is ready, when the states for all pool members
     * are ready.
     */
    private def isPoolReady(update: Any): JBoolean = {
        assertThread()
        val ready: JBoolean = poolMembers.forall(_._2.isReady)
        log.debug("Pool ready: {}", ready)
        ready
    }

    /**
     * The method is called when the pool is deleted. It triggers a completion
     * of the device observable, by completing all pool member subjects.
     */
    private def poolDeleted(): Unit = {
        assertThread()
        log.debug("Pool deleted")

        for (poolMemberState <- poolMembers.values) {
            poolMemberState.complete()
        }
        poolMembersSubject.onCompleted()
    }

    /**
     * Processes updates from the topology pool observable. This examines the
     * addition/removal of the pool members, and add/removes the corresponding
     * pool member observables.
     *                +---------------------+
     * store[Pool]--->| onNext(poolUpdated) |---> Observable[TopologyBool]
     *                +---------------------+
     *                           |
     *          Add: poolMembersSubject onNext poolObservable
     *          Remove: poolObservable complete()
     */
    private def poolUpdated(p: TopologyPool): Unit = {
        assertThread()

        pool = p

        val memberIds = pool.getPoolMemberIdsList.asScala.map(_.asJava).toSet
        log.debug("Update for pool with members {}", memberIds)

        // Complete the observables for the members no longer part of this pool.
        for ((memberId, memberState) <- poolMembers.toList
             if !memberIds.contains(memberId)) {
            memberState.complete()
            poolMembers -= memberId
        }

        // Create observables for the new members of this pool, and notify them
        // on the pool members observable.
        for (memberId <- memberIds if !poolMembers.contains(memberId)) {
            val memberState = new PoolMemberState(memberId, vt)
            poolMembers += memberId -> memberState
            poolMembersSubject onNext memberState.observable
        }
    }

    /** Processes updates from a pool's member. */
    private def poolMemberUpdated(poolMember: SimulationPoolMember)
    : TopologyPool = {
        assertThread()
        log.debug("Pool member updated {}", poolMember)
        pool
    }

    /**
     * Maps the [[TopologyPool]] to a [[SimulationPool]] device.
     */
    private def buildDevice(update: Any): SimulationPool = {
        assertThread()

        // Compute the active and disabled pool members.
        val members = poolMembers.values.map(_.poolMember)
        val activePoolMembers = members.filter(_.isUp).toArray
        val disabledPoolMembers = members.filterNot(_.adminStateUp).toArray

        // Create the simulation pool.
        val device = new SimulationPool(
            pool.getId,
            pool.getAdminStateUp,
            PoolLBMethod.fromProto(pool.getLbMethod),
            activePoolMembers,
            disabledPoolMembers)

        log.debug("Pool ready: {}", device)

        device
    }
}
