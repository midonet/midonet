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

package org.midonet.cluster.services.containers.schedulers

import java.util.UUID

import scala.collection.JavaConverters._

import rx.Observable

import org.midonet.cluster.models.Topology.PortGroup
import org.midonet.cluster.util.UUIDUtil._
import org.midonet.containers.{Context, CollectionTracker, ObjectTracker}
import org.midonet.util.functors.{makeAction0, makeAction1, makeFunc1, makeFunc3}

/** Processes notifications for the container service running at the hosts where
  * the ports from the specified port group are bound, given that the ports are
  * active. The class returns an observable, which emits notifications when the
  * group, active state of the ports or the container service on any of the
  * hosts where the ports are bound has changed. The observable completes either
  * when the port group is deleted, or when calling the `complete` method.
  *
  * The tracker filters all errors for the member port and host observables,
  * such that when a member port or host emits an error, it is removed from the
  * group. However, the tracker does not filter errors emitted by the port
  * group observable.
  */
class PortGroupTracker(portGroupId: UUID, context: Context)
    extends ObjectTracker[PortGroupEvent] {

    private var currentPortGroup: PortGroup = _

    // The tracker for the ports in the port group.
    private val portsTracker =
        new CollectionTracker[PortTracker, PortEvent](context) {
            protected override def newMember(portId: UUID): PortTracker = {
                new PortTracker(portId, context)
            }
        }

    // The tracker for the hosts where the ports in the port group are bound.
    private val hostsTracker =
        new CollectionTracker[HostTracker, HostEvent](context) {
            protected override def newMember(hostId: UUID): HostTracker = {
                new HostTracker(hostId, context)
            }
        }

    // Handles notifications for the port group.
    private val portGroupObservable = context.store
        .observable(classOf[PortGroup], portGroupId)
        .distinctUntilChanged()
        .observeOn(context.scheduler)
        .doOnNext(makeAction1(portGroupUpdated))
        .doOnCompleted(makeAction0(portGroupDeleted()))

    override val observable = Observable
        .combineLatest[HostsEvent, PortsEvent, PortGroup, PortGroupEvent](
            hostsTracker.observable,
            portsTracker.observable.doOnNext(makeAction1(portsUpdated)),
            portGroupObservable,
            makeFunc3(buildEvent))
        .distinctUntilChanged()
        .filter(makeFunc1(_ => isReady))
        .takeUntil(mark)

    /** Indicates whether the port group tracker has received the data from
      * storage for the port group and all port and host trackers.
      */
    override def isReady: Boolean = {
        (currentPortGroup ne null) && hostsTracker.isReady &&
            portsTracker.isReady
    }

    /** Processes updates for the port group.
      */
    private def portGroupUpdated(portGroup: PortGroup): Unit = {
        val memberIds = portGroup.getPortIdsList.asScala.map(_.asJava).toSet

        context.log debug s"Port group $portGroupId updated with members " +
                          s"$memberIds"

        currentPortGroup = portGroup
        portsTracker watch memberIds
    }

    /** Processes the completion of the port group observable.
      */
    private def portGroupDeleted(): Unit = {
        context.log debug s"Port group $portGroupId deleted"

        portsTracker.complete()
        hostsTracker.complete()
    }

    /** Processes updates for the ports.
      */
    private def portsUpdated(ports: PortsEvent): Unit = {
        val boundActivePorts =
            ports.filter(p => p._2.active && (p._2.hostId ne null))
        val hostIds = boundActivePorts.values.map(_.hostId).toSet

        context.log debug s"Ports updated: $ports of which bound and active: " +
                          s"$boundActivePorts"

        hostsTracker watch hostIds
    }

    /** Builds the port group event for this port group tracker.
      */
    private def buildEvent(hostsEvent: HostsEvent, portsEvent: PortsEvent,
                           portGroup: PortGroup): PortGroupEvent = {
        ref = PortGroupEvent(portGroupId, hostsEvent)
        ref
    }

}
