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

import scala.collection.mutable
import scala.collection.JavaConverters._
import scala.collection.mutable.ArrayBuffer

import rx.Observable
import rx.subjects.PublishSubject

import org.midonet.cluster.models.Topology.{LoadBalancer => TopologyLb}
import org.midonet.cluster.util.UUIDUtil._
import org.midonet.midolman.simulation.{LoadBalancer => SimulationLb, Pool => SimulationPool, Vip}
import org.midonet.midolman.topology.DeviceMapper.DeviceState
import org.midonet.util.collection._
import org.midonet.util.functors._

final class LoadBalancerMapper(loadBalancerId: UUID, vt: VirtualTopology)
    extends VirtualDeviceMapper[SimulationLb](loadBalancerId, vt) {

    override def logSource = s"org.midonet.devices.l4lb.l4lb-$loadBalancerId"

    private var loadBalancer: TopologyLb = null
    private var device: SimulationLb = null
    private var poolIds: Seq[UUID] = null
    private val pools = mutable.HashMap[UUID, DeviceState[SimulationPool]]()

    private val poolsSubject = PublishSubject.create[Observable[SimulationPool]]
    private lazy val poolsObservable = Observable
        .merge(poolsSubject)
        .map[TopologyLb](makeFunc1 { pool =>
            assertThread()
            log.debug("Pool updated {}", pool)
            loadBalancer
        })

    private lazy val loadBalancerObservable = vt.store
        .observable[TopologyLb](classOf[TopologyLb], loadBalancerId)
        .observeOn(vt.vtScheduler)
        .doOnCompleted(makeAction0(loadBalancerDeleted()))
        .doOnNext(makeAction1(loadBalancerUpdated))

    // The output device observable for the load-balancer mapper:
    //
    //                             on VT scheduler
    //                        +---------------------+
    // store[Load-Balancer]-->| loadBalancerDeleted |--+
    //                        +---------------------+  |
    // onNext(VT.observable[VIP])                      |
    //      +--------+---------------------------------+
    //      |        |
    //      |        |  +-------------------------+
    // Obs[Obs[VIP]]-+->|filter(loadBalancerReady)|-> device: simLB
    //                  +-------------------------+
    //
    // WARNING! The device observable merges the vips and load-balancer
    // observables. The vip publish subject must be added to the merge before
    // observables that may trigger their update, such as the load-balancer
    // observable, which ensures they are subscribed to before emitting any
    // updates.
    protected override lazy val observable: Observable[SimulationLb] =
        Observable.merge(poolsObservable,
                         loadBalancerObservable)
            .filter(makeFunc1(isLoadBalancerReadyAndChanged))
            .map[SimulationLb](makeFunc1(_ => device))

    /** Processes updates for the load-balancer. */
    private def loadBalancerUpdated(lb: TopologyLb): Unit = {
        assertThread()
        loadBalancer = lb

        poolIds = loadBalancer.getPoolIdsList.asScala.map(_.asJava)
        log.debug("Load-balancer updated with pools {}", poolIds)

        // Update the device state for the pools.
        updateTopologyDeviceState(poolIds.toSet, pools, poolsSubject)
    }

    /** The method is called when the load-balancer is deleted. It triggers a
      * completion of the device observable, by completing all pool subjects. */
    private def loadBalancerDeleted(): Unit = {
        assertThread()
        log.debug("Load-balancer deleted")

        completeDeviceState(pools)
        poolsSubject.onCompleted()
    }

    /** Returns whether the load-balancer device is ready, when the load-balancer
      * and all the pools are received. */
    private def isLoadBalancerReadyAndChanged(update: Any): Boolean = {
        assertThread()
        val ready = (loadBalancer ne null) && pools.values.forall(_.isReady)
        val lb = if (ready) buildLoadBalancer else null
        val changed = lb != device

        log.debug("Load-balancer ready {} changed {}", Boolean.box(ready),
                  Boolean.box(changed))

        if (ready && changed) {
            log.debug("Building load-balancer {}", lb)
            device = lb
        }

        ready && changed
    }

    /** Builds the load-balancer simulation device. */
    private def buildLoadBalancer: SimulationLb = {
        // Aggregate the load-balancer VIPs in pool order.
        val vips = new ArrayBuffer[Vip]()
        for (pool <- poolIds.flatMap(pools.get)) vips ++= pool.device.vips

        new SimulationLb(loadBalancerId,
                         loadBalancer.getAdminStateUp,
                         if (loadBalancer.hasRouterId)
                             loadBalancer.getRouterId.asJava
                         else null,
                         vips.toArray)
    }

}
