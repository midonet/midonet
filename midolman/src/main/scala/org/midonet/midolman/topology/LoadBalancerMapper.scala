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

import scala.collection.JavaConverters._
import scala.collection.mutable

import rx.Observable
import rx.subjects.PublishSubject

import org.midonet.cluster.data.ZoomConvert
import org.midonet.cluster.models.Topology.{LoadBalancer => TopologyLB, VIP => TopologyVIP}
import org.midonet.cluster.util.UUIDUtil._
import org.midonet.midolman.simulation.{LoadBalancer => SimLB, VIP => SimVip}
import org.midonet.midolman.topology.LoadBalancerMapper.VipState
import org.midonet.util.functors._

object LoadBalancerMapper {
    /**
     * Stores the state for a VIP and exposes an observable for it. If the vip
     * is removed from the load-balancer, we unsubscribe from it by calling
     * the complete() method below.
     */
    private final class VipState(vipId: UUID, vt: VirtualTopology) {
        private var currentVip: SimVip = null

        private val mark = PublishSubject.create[TopologyVIP]
        /** The vip observable, notifications on the VT thread.
          * The filter discards any vip without a back-reference to the
          * load balancer, which may occur when removing the VIP from the
          * load balancer. */
        val observable = vt.store.observable(classOf[TopologyVIP], vipId)
            .filter(makeFunc1(_.hasLoadBalancerId))
            .map[SimVip](makeFunc1(ZoomConvert.fromProto(_, classOf[SimVip])))
            .observeOn(vt.vtScheduler)
            .doOnNext(makeAction1(currentVip = _))
            .distinctUntilChanged
            .takeUntil(mark)

        /** Completes the observable corresponding to this vip state. */
        def complete() = mark.onCompleted()
        /** Gets the current vip or null, if none is set. */
        @Nullable
        def vip: SimVip = currentVip
        /** Indicates whether the vip state has received the vip data. */
        def isReady: Boolean = currentVip ne null
    }
}

final class LoadBalancerMapper(lbId: UUID, vt: VirtualTopology)
    extends VirtualDeviceMapper[SimLB](lbId, vt) {

    private var currentLB: TopologyLB = null
    private val vipSubject = PublishSubject.create[Observable[SimVip]]()
    private val vips = mutable.HashMap[UUID, VipState]()

    // Store the order in which VIPs appear in the load-balancer protocol
    // buffer.
    private var vipIdsInOrder: Seq[UUID] = null

    // The last emitted load-balancer
    private var device: SimLB = null

    private def buildDevice(): SimLB = {
        val vipsArray = new Array[SimVip](vipIdsInOrder.size)
        var index = 0
        for (vipId <- vipIdsInOrder)
            { vipsArray(index) = vips(vipId).vip; index += 1 }

        val lb = new SimLB(lbId, currentLB.getAdminStateUp,
                           if (currentLB.hasRouterId)
                               currentLB.getRouterId.asJava
                           else null,
                           vipsArray)
        lb
    }

    private def loadBalancerReady(update: Any): Boolean = {
        assertThread()

        update match {
            case loadBalancer: TopologyLB =>
                vipIdsInOrder = loadBalancer.getVipIdsList.asScala.map(_.asJava)
                val vipIds = vipIdsInOrder.toSet
                log.debug("Load-balancer update, VIPs {}", vipIds)

                // Complete the observables for the vips no longer part
                // of this load-balancer.
                for ((vipId, vipState) <- vips.toList
                     if !vipIds.contains(vipId)) {
                    vipState.complete()
                    vips -= vipId
                }

                // Create state for the new vips of this load-balancer, and
                // notify their observable on the vips observable.
                for (vipId <- vipIds if !vips.contains(vipId)) {
                    val vipState = new VipState(vipId, vt)
                    vips += vipId -> vipState
                    vipSubject onNext vipState.observable
                }

                currentLB = loadBalancer
            case vip: SimVip =>
                log.debug("Update for VIP: {}", vip)
            case _ =>
                log.warn("Unexpected update of class: {}, ignoring",
                         update.getClass)
        }

        if ((currentLB ne null) && vips.forall(_._2.isReady)) {
            val lb = buildDevice()
            if (lb != device) {
                log.debug("Load-balancer ready: {}", lb)

                device = lb
                return true
            }
        }
        false
    }

    private def loadBalancerDeleted(): Unit = {
        assertThread()

        log.debug("Load-balancer deleted")
        vipSubject.onCompleted()
        vips.values.foreach(_.complete())
        vips.clear()
    }

    private lazy val loadBalancerObservable =
        vt.store.observable[TopologyLB](classOf[TopologyLB], lbId)
            .observeOn(vt.vtScheduler)
            .doOnCompleted(makeAction0(loadBalancerDeleted()))

    // The output device observable for the load-balancer mapper:
    //
    //                             on VT scheduler
    //                        +---------------------+
    // store[Load-Balancer]-->| loadBalancerDeleted |--+
    //                        +---------------------+  |
    // onNext(VT.observable[VIP])                      |
    //     +--------+----------------------------------+
    //     |        |
    //     |        |  +-------------------------+
    //Obs[Obs[VIP]]-+->|filter(loadBalancerReady)|-> device: simLB
    //                 +-------------------------+
    //
    protected override lazy val observable: Observable[SimLB] =
        // WARNING! The device observable merges the vips and load-balancer
        // observables. The vip publish subject must be added to the merge before
        // observables that may trigger their update, such as the load-balancer
        // observable, which ensures they are subscribed to before emitting any
        // updates.
        Observable.merge[Any](Observable.merge(vipSubject),
                              loadBalancerObservable)
            .filter(makeFunc1(loadBalancerReady))
            .map[SimLB](makeFunc1(_ => device))
}
