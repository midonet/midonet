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
import java.util.concurrent.atomic.AtomicBoolean

import scala.collection.mutable

import com.typesafe.scalalogging.Logger

import org.slf4j.LoggerFactory.getLogger

import rx.Observable.OnSubscribe
import rx.{Observable, Subscriber}

import org.midonet.cluster.models.Topology.ServiceContainer
import org.midonet.cluster.services.containers.ContainerService
import org.midonet.cluster.util.UUIDUtil._
import org.midonet.cluster.{ContainersConfig, ContainersLog}
import org.midonet.containers.{Context, ObjectTracker}
import org.midonet.util.functors.{makeAction0, makeFunc1}

/** This service is responsible for scheduling each [[ServiceContainer]]
  * from the topology to one of the hosts determined by the host
  * selection policy.
  *
  * For each container, the service scheduler will create a [[ContainerScheduler]]
  * instance, that will handle the scheduling for that particular container,
  * including tracking the scheduling timeout and the container state as
  * reported by the host where the container has been scheduled.
  *
  * This class does not apply the actual scheduling of the container and it
  * depends on a client to listen to scheduling changes and apply the
  * necessary changes in the topology to ensure that the container is spawned
  * and bound to the virtual topology at the right host.
  *
  * To this end, the [[ServiceScheduler]] exposes an observable that emits
  * notifications when the scheduling of a container has changed.
  */
class ServiceScheduler(context: Context, config: ContainersConfig)
    extends ObjectTracker[SchedulerEvent] {

    private val log = Logger(getLogger(ContainersLog))

    private val subscribed = new AtomicBoolean(false)

    // Container indices.
    private val containers = new mutable.HashMap[UUID, ContainerScheduler]

    // A provider that returns a host selection for a given scheduling policy.
    private val selectorProvider = new HostSelectorProvider(context)

    private val containersObservable = context.store
        .observable(classOf[ServiceContainer])
        .onBackpressureBuffer(ContainerService.SchedulingBufferSize)
        .observeOn(context.scheduler)
        .flatMap(makeFunc1(_.take(1)))
        .flatMap(makeFunc1(containerCreated))
        .takeUntil(mark)

    /** An observable that emits notifications for this scheduler. The scheduler
      * observable allows only one subscriber guaranteeing a consistent stream
      * of scheduling updates for all containers.
      */
    override val observable = Observable.create(new OnSubscribe[SchedulerEvent] {
        override def call(child: Subscriber[_ >: SchedulerEvent]): Unit = {
            if (subscribed.compareAndSet(false, true)) {
                containersObservable subscribe child
            } else {
                throw new IllegalStateException("The scheduler accepts only " +
                                                "one subscription")
            }
        }
    })

    override val isReady = true

    /** Gets the identifier set for current containers.
      */
    def containerIds: Set[UUID] = containers.keySet.toSet

    /** Handles the creation of a new container, by creating a container
      * scheduler to handle the scheduling for that container. The method
      * adds the container scheduler to the containers map, and returns
      * the observable for the new container scheduler.
      */
    private def containerCreated(container: ServiceContainer)
    : Observable[SchedulerEvent] = {
        log info s"New service container ${container.getId.asJava}"
        val scheduler = new ContainerScheduler(container.getId, context,
                                               config, selectorProvider)
        containers += container.getId.asJava -> scheduler
        scheduler.observable
                 .doOnCompleted(makeAction0(containerCompleted(container.getId)))
    }

    /** Handles the completion of a container scheduler observable, by removing
      * the container from the containers map.
      */
    private def containerCompleted(containerId: UUID): Unit = {
        log info s"Scheduling completed for container $containerId"
        containers -= containerId
    }
}
