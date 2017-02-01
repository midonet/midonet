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

package org.midonet.cluster.services.containers.schedulers

import java.util.UUID

import scala.collection.JavaConverters._

import rx.Observable

import org.midonet.cluster.models.Topology.HostGroup
import org.midonet.cluster.services.containers.ContainerService
import org.midonet.cluster.util.UUIDUtil._
import org.midonet.containers.{CollectionTracker, Context, ObjectTracker}
import org.midonet.util.functors.{makeAction0, makeAction1, makeFunc1, makeFunc2}

/** Processes notifications for the container service running at the hosts in
  * the specified host group. The class returns an observable, which emits
  * notifications when the group or the running state of the container service
  * on any of the hosts in the group has changed. The observable completes
  * either when the host group is deleted, or when calling the `complete`
  * method.
  *
  * The tracker filters all error for the member host observable, such that
  * when a member host emits an error it is removed from the group. However,
  * the tracker does not filter errors emitted by the host group observable.
  */
class HostGroupTracker(hostGroupId: UUID, context: Context)
    extends ObjectTracker[HostGroupEvent] {

    private var currentHostGroup: HostGroup = null

    // The tracker for the hosts in the host group.
    private val hostsTracker =
        new CollectionTracker[HostTracker, HostEvent](context) {
            protected override def newMember(hostId: UUID): HostTracker = {
                new HostTracker(hostId, context)
            }
        }

    // Handles notifications for the host group.
    private val hostGroupObservable = context.store
        .observable(classOf[HostGroup], hostGroupId)
        .distinctUntilChanged()
        .onBackpressureBuffer(ContainerService.SchedulingBufferSize)
        .observeOn(context.scheduler)
        .doOnNext(makeAction1(hostGroupUpdated))
        .doOnCompleted(makeAction0(hostGroupDeleted()))

    /** An observable that emits notifications for this host group tracker.
      */
    override val observable = Observable
        .combineLatest[HostsEvent, HostGroup, HostGroupEvent](
            hostsTracker.observable,
            hostGroupObservable,
            makeFunc2(buildEvent))
        .distinctUntilChanged()
        .filter(makeFunc1(_ => isReady))
        .takeUntil(mark)

    /** Indicates whether the host group tracker has received the data from
      * storage for the host group and all host trackers.
      */
    override def isReady: Boolean = {
        (currentHostGroup ne null) && hostsTracker.isReady
    }

    /** Processes updates for the host group.
      */
    private def hostGroupUpdated(hostGroup: HostGroup): Unit = {
        val memberIds = hostGroup.getHostIdsList.asScala.map(_.asJava).toSet

        context.log debug s"Host group $hostGroupId updated with members " +
                          s"$memberIds"

        currentHostGroup = hostGroup
        hostsTracker watch memberIds
    }

    /** Processes the completion of the host group observable.
      */
    private def hostGroupDeleted(): Unit = {
        context.log debug s"Host group $hostGroupId deleted"

        hostsTracker.complete()
    }

    /** Builds the host group event for this host group tracker.
      */
    private def buildEvent(hostsEvent: HostsEvent, hostGroup: HostGroup)
    : HostGroupEvent = {
        ref = HostGroupEvent(hostGroupId, hostsEvent)
        ref
    }

}
