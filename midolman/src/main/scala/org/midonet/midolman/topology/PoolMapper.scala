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

import org.midonet.cluster.models.Topology.{Pool => TopologyPool, PoolMember => TopologyPoolMember, Vip => TopologyVip}
import org.midonet.cluster.util.UUIDUtil._
import org.midonet.midolman.simulation.{Pool => SimulationPool, PoolMember => SimulationPoolMember, Vip => SimulationVip}
import org.midonet.midolman.state.l4lb.PoolLBMethod
import org.midonet.midolman.topology.DeviceMapper.DeviceState
import org.midonet.util.functors.{makeAction0, makeAction1, makeFunc1}

/**
 * A device mapper that exposes an [[rx.Observable]] with notifications for a
 * layer-4 load balancer pool. The pool observable combines the latest updates
 * from both the topology pool object and the pool members.
 */
final class PoolMapper(poolId: UUID, vt: VirtualTopology)
    extends VirtualDeviceMapper[SimulationPool](poolId, vt) {

    override def logSource = s"org.midonet.devices.pool.pool-$poolId"

    private var pool: TopologyPool = null
    private var memberIds: Seq[UUID] = null
    private var vipIds: Seq[UUID] = null
    private val members =
        new mutable.HashMap[UUID, DeviceState[SimulationPoolMember]]
    private val vips =
        new mutable.HashMap[UUID, DeviceState[SimulationVip]]

    // A subject that emits a pool member observable for every pool member added
    // to the pool.
    private lazy val membersSubject = PublishSubject
        .create[Observable[SimulationPoolMember]]
    private lazy val membersObservable = Observable
        .merge(membersSubject)
        .map[TopologyPool](makeFunc1 { poolMember =>
            assertThread()
            log.debug("Pool member updated {}", poolMember)
            pool
        })

    private lazy val vipsSubject = PublishSubject
        .create[Observable[SimulationVip]]
    private lazy val vipsObservable = Observable
        .merge(vipsSubject)
        .map[TopologyPool](makeFunc1 { vip =>
            assertThread()
            log.debug("VIP updated {}", vip)
            pool
        })

    private lazy val poolObservable = vt.store
        .observable(classOf[TopologyPool], poolId)
        .observeOn(vt.vtScheduler)
        .doOnCompleted(makeAction0(poolDeleted()))
        .doOnNext(makeAction1(poolUpdated))

    // The output device observable for the pool mapper.
    //
    //               on VT scheduler
    //               +--------------------------+  +---------------------+
    // store[Port]-->| onCompleted(poolDeleted) |->| onNext(poolUpdated) |
    //               +--------------------------+  +----+-----------+----+
    //   onNext(VT.observable[Port])                    |           |
    //   +----------------------------------------------+           |
    //   |                   +------------------+                   |
    // Obs[Obs[PoolMember]]->| map(portUpdated) |-------------------+ merge
    //                       +------------------+                   |
    //   +----------------------------------------------------------+
    //   |  +---------------------+
    //   +->| filter(isPoolReady) |-> device: SimulationPool
    //      +---------------------+
    protected override val observable = Observable
        .merge(membersObservable, vipsObservable, poolObservable)
        .filter(makeFunc1(isPoolReady))
        .map[SimulationPool](makeFunc1(buildPool))

    /**
     * Indicates that the pool is ready, which occurs when the states for all
     * pool members are ready.
     */
    private def isPoolReady(update: Any): JBoolean = {
        assertThread()
        val ready = (pool ne null) &&
                    members.values.forall(_.isReady) &&
                    vips.values.forall(_.isReady)
        log.debug("Pool ready: {}", Boolean.box(ready))
        ready
    }

    /**
     * Processes updates from the topology pool observable. This examines the
     * addition/removal of the pool members, and add/removes the corresponding
     * pool member observables.
     *                +---------------------+
     * store[Pool]--->| onNext(poolUpdated) |---> Observable[TopologyPool]
     *                +---------------------+
     *                           |
     *          Add: poolMembersSubject onNext poolObservable
     *          Remove: poolObservable complete()
     */
    private def poolUpdated(p: TopologyPool): Unit = {
        assertThread()

        pool = p

        memberIds = pool.getPoolMemberIdsList.asScala.map(_.asJava)
        vipIds = pool.getVipIdsList.asScala.map(_.asJava)
        log.debug("Pool updated with members {} VIPs {}", memberIds, vipIds)

        // Update the device state for pool members.
        updateZoomDeviceState[SimulationPoolMember, TopologyPoolMember](
            memberIds.toSet, members, membersSubject, vt)

        // Update the device state for VIPs.
        updateZoomDeviceState[SimulationVip, TopologyVip](
            vipIds.toSet, vips, vipsSubject, vt)
    }

    /**
     * The method is called when the pool is deleted. It triggers a completion
     * of the device observable, by completing all pool member subjects.
     */
    private def poolDeleted(): Unit = {
        assertThread()
        log.debug("Pool deleted")

        completeDeviceState(members)
        completeDeviceState(vips)
        membersSubject.onCompleted()
        vipsSubject.onCompleted()
    }

    /**
     * Maps the [[TopologyPool]] to a [[SimulationPool]] device.
     */
    private def buildPool(pool: TopologyPool): SimulationPool = {
        assertThread()

        // Compute the active and disabled pool members.
        val allMembers = memberIds.flatMap(members.get).map(_.device)
        val activePoolMembers = allMembers.filter(_.isUp)
        val disabledPoolMembers = allMembers.filterNot(_.adminStateUp)
        val allVips = vipIds.flatMap(vips.get).map(_.device)

        // Create the simulation pool.
        val device = new SimulationPool(
            pool.getId,
            pool.getAdminStateUp,
            if (pool.hasLbMethod) PoolLBMethod.fromProto(pool.getLbMethod) else null,
            if (pool.hasHealthMonitorId) pool.getHealthMonitorId else null,
            if (pool.hasLoadBalancerId) pool.getLoadBalancerId else null,
            allMembers.toArray,
            activePoolMembers.toArray,
            disabledPoolMembers.toArray,
            allVips.toArray)
        log.debug("Building pool {}", device)
        device
    }
}
