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

import java.util
import java.util.UUID
import java.util.concurrent.{ConcurrentHashMap, ExecutorService}

import scala.collection.mutable
import scala.concurrent.ExecutionContext
import scala.util.control.NonFatal

import com.google.common.collect.ArrayListMultimap
import com.google.inject.Inject
import com.typesafe.scalalogging.Logger

import org.slf4j.LoggerFactory.getLogger

import rx.schedulers.Schedulers
import rx.subjects.PublishSubject
import rx.{Observable, Observer}

import org.midonet.cluster.data.storage.{StateStorage, Storage}
import org.midonet.cluster.models.Commons
import org.midonet.cluster.models.Topology.{Host, ServiceContainer, ServiceContainerGroup}
import org.midonet.cluster.services.DeviceWatcher
import org.midonet.cluster.services.MidonetBackend.AliveKey
import org.midonet.cluster.util.UUIDUtil._
import org.midonet.cluster.util.logging.ProtoTextPrettifier._

/** The type of notifications emitted by the scheduler. Change the container
  * param to a ServiceContainer (instead of UUID) once the container state
  * monitoring is in place.
  *
  * TODO: replace group with group id, and force reload from storage (or
  * cache them)
  */
trait ContainerEvent
case class Allocation(container: ServiceContainer, group: ServiceContainerGroup,
                      hostId: UUID) extends ContainerEvent {
    def revoked = Deallocation(container, hostId)
}
case class Deallocation(container: ServiceContainer, hostId: UUID)
    extends ContainerEvent

/** This service is responsiblef for scheduling each
  * [[org.midonet.cluster.models.Topology.ServiceContainer]] instance found in
  * the system to one of the hosts determined by the Allocation policy.
  *
  * For each of the candidate hosts, the Scheduler will track their liveness
  * and perform the necessary realloadtion whenever the host assigned to a
  * given container goes down.
  *
  * This class does not apply the actual allocation of the container and it
  * depends on a client to listen to allocation changes and apply the
  * necessary changes in the topology to ensure that the container is spawned
  * and bound to the virtual topology at the right host.
  *
  * The current implementation is based on a Round Robin + failure detection
  * mechanism. This doesn't take into account the effect of churn on the
  * scheduling so other algorithms might be implemented in the future if
  * necessary (e.g. consistent hashing, load based, etc.).
  */
class Scheduler @Inject()(store: Storage, stateStore: StateStorage,
                          executor: ExecutorService) {

    private val log = Logger(getLogger("org.midonet.cluster.containers.container-scheduler"))
    private implicit val ec = ExecutionContext.fromExecutor(executor)
    private val scheduler = Schedulers.from(executor)

    /* State indices */
    private val hostToAllocations = ArrayListMultimap.create[UUID, Allocation]()
    private val containerToHost = new ConcurrentHashMap[UUID, UUID]
    private val containers = new util.HashMap[UUID, ServiceContainer]()

    /* Host selectors indexed by Service Container Group */
    private val scgToSelector = new util.HashMap[UUID, HostSelector]()

    /* A factory to retrieve the right allocation strategy for a container */
    private val selectorFactory = new HostSelectorFactory(store, scheduler)

    /* Service Containers that we haven't been able to alocate yet */
    private var incompleteAllocations = mutable.Set.empty[ServiceContainerGroup]

    /* Tracks liveness of hosts, and tells us about it */
    private val livenessTracker = new LivenessTracker[Host](stateStore,
                                                            AliveKey,
                                                            scheduler,
                                                            log)

    /* Message bus to notify about allocation events */
    private val containerLocationSubject = PublishSubject.create[ContainerEvent]

    /* Watches all Service Container Groups in the system. */
    private val groupWatcher = new DeviceWatcher[ServiceContainerGroup](
        store, scheduler, onContainerGroupUpdate, onContainerGroupDelete,
        log = log)

    /* Watches all Service Containers in the system */
    private val containerWatcher = new DeviceWatcher[ServiceContainer](
        store, scheduler, onContainerUpdate, onContainerDelete, log = log)

    livenessTracker.observable
        .observeOn(scheduler)
        .subscribe(new Observer[Liveness] {
            override def onCompleted(): Unit = {
                log.info("Host liveness no longer watched (stream completed)")
            }
            override def onError(t: Throwable): Unit = {
                log.warn("Host liveness no longer watched (stream error)", t)
            }
            override def onNext(t: Liveness): Unit = t match {
                case Alive(id) => onHostAlive(id)
                case Dead(id) => onHostDeath(id)
            }
        })

    private def onHostDeath(hostId: UUID): Unit = {
        log.debug(s"Host $hostId died.")
        val allocations = hostToAllocations.get(hostId)
        val groupsToReallocate = mutable.HashSet.empty[ServiceContainerGroup]
        val it = allocations.iterator()
        while (it.hasNext) {
            val alloc= it.next()
            groupsToReallocate += alloc.group
            containerToHost.remove(alloc.container.getId.asJava)
            containerLocationSubject onNext alloc.revoked
            log.debug(s"Container ${makeReadable(alloc.container.getId)} needs " +
                      s"reallocation out of $hostId")
        }
        groupsToReallocate foreach allocateGroup
    }

    private def onHostAlive(hostId: UUID): Unit = {
        log.debug(s"Host $hostId became alive.")
        val previousIncomplete = incompleteAllocations
        incompleteAllocations = mutable.Set.empty
        previousIncomplete foreach { group =>
            // This has room for improvement, instead of scanning all pending
            // groups we could filter those that have this host in their
            // candidate list.  For now, given that we're supporting the
            // ANYWHERE policy, this filter would be irrelevant and just
            // complicate the code too much.
            allocateGroup(group)
        }
    }

    /** The service scheduler monitors any change on service container groups
      * created, updated and deleted to allocate containers to the appropriate
      * hosts according to the specified policy in the container group. This
      * method starts monitoring container groups and emits
      * [[Allocation]] events through the observable returned.
      *
      * IMPORTANT: make sure to subscribe to the event observable before
      * starting to schedule.
      *
      * @return [[rx.Observable]][[Allocation]] Observable where
      *         the scheduler emits the mappings of containers to host.
      */
    def startScheduling(): Unit = {
        containerWatcher.subscribe()
        groupWatcher.subscribe()
    }

    def eventObservable: Observable[ContainerEvent] = {
        containerLocationSubject.asObservable()
    }

    /** Clear the state of this scheduler, unsubscribing from the service
      * container groups being monitored.
      */
    def stopScheduling(): Unit = {
        groupWatcher.unsubscribe()
        containerWatcher.unsubscribe()
        val it = scgToSelector.values().iterator()
        while (it.hasNext) {
            it.next().dispose()
        }
    }

    private def onContainerGroupDelete(scgId: Object): Unit = {
        val id = scgId match {
            case protoId: Commons.UUID => protoId.asJava
            case theId: UUID => theId
            case _ => return
        }
        log.debug(s"Container group $id deleted.")
        val selector = scgToSelector.remove(id)
        if (selector ne null)
            selector.dispose()
    }

    /** Method called by the device watcher whenever a [[ServiceContainerGroup]]
      * on the first load from NSDB, and then on subsequent updates (we ignore
      * updates because they are not relevant for us, the allocation policy is
      * static).
      *
      * When invoked, it'll watch changes on the discarded hosts and candidate
      * hosts of the specific host selector of this service container group.
      */
    private def onContainerGroupUpdate(group: ServiceContainerGroup): Unit = {
        val scgId = group.getId.asJava
        if (!scgToSelector.containsKey(scgId)) {
            log.debug(s"New container group $scgId")
            val selector = selectorFactory.getHostSelector(group)
            scgToSelector.put(scgId, selector)
        }
        // We need to reevaluation allocations in case a container was
        // added later to the group (as with the midonet-cli)
        allocateGroup(group)
    }

    def onContainerUpdate(container: ServiceContainer): Unit = {
        containers.put(container.getId.asJava, container)
    }

    def onContainerDelete(container: ServiceContainer): Unit = {
        containers.remove(container.getId.asJava)
        val hostId = containerToHost.remove(container.getId.asJava)
        containerLocationSubject onNext Deallocation(container, hostId)
    }

    /** Takes a group, and gets as many containers as possible allocated. */
    def allocateGroup(group: ServiceContainerGroup): Unit = {
        log.debug(s"Review allocations for group: ${makeReadable(group)}")
        val scgId = group.getId.asJava
        val selector = scgToSelector.get(scgId)
        if (selector eq null) {
            log.info("Unexpected: no selector cached when trying to " +
                     s"reallocate containers in group $scgId")
            return
        }
        selector.candidateHosts.flatMap { hostSet =>
            log.debug(s"Candidate hosts for group $scgId: $hostSet")
            livenessTracker.watch(hostSet)
        } recover {
            case NonFatal(t) =>
                log.warn(s"Can't fetch hosts for container group $scgId", t)
                Set.empty[UUID]
        } onSuccess {
            case hosts =>
                if (createMappings(group, hosts.toSeq)) {
                    log.debug(s"Container group $scgId was fully allocated")
                } else {
                    log.debug(s"Container group $scgId not fully allocated")
                    incompleteAllocations.add(group)
                }
                log.debug(s"Pending allocations $incompleteAllocations")
        }
    }

    /** Algorithm to select which containers go to which hosts. This implements
      * a basic Round Robin policy in case there are fewer hosts available than
      * containers. The load is also spread among eligible hosts instead of
      * scheduling all containers to the same host.
      *
      * @return whether all containers were allocated.
      */
    private def createMappings(group: ServiceContainerGroup,
                               candidates: Seq[UUID]): Boolean = {

        val scgId = group.getId.asJava
        log.debug(s"Evaluating host allocation for containers in group $scgId" +
                  s" with candidates: $candidates")

        // Only consider those hosts that are alive
        val eligible = candidates.filter(livenessTracker.isAlive)
        log.debug(s"Eligible hosts: $eligible")

        // Spot containers that are already allocated.  Note that we don't
        // remove previous allocations, only onDeadHost and onContainerDelete
        // does that.
        val it = group.getServiceContainerIdsList.iterator()
        val unallocatedContainers = new mutable.HashSet[UUID]
        while (it.hasNext) {
            val containerId = it.next().asJava
            val hostId = containerToHost.get(containerId)
            if (hostId eq null) {
                log.debug(s"Container $containerId needs a host")
                unallocatedContainers += containerId
            } else {
                log.debug(s"Container $containerId can stay at $hostId")
            }
        }

        if (unallocatedContainers.isEmpty) {
            log.debug(s"No containers remaining for allocation.")
            return true
        }

        if (eligible.isEmpty) {
            log.debug(s"No hosts available to allocate group ${makeReadable(group)}")
            return false
        }

        log.debug(s"Reallocating containers: $unallocatedContainers")
        var i = 0
        val newMappings = unallocatedContainers map { containerId =>
            val idx = i % eligible.size
            i = i + 1
            val hostId = eligible(idx)
            val allocation = Allocation(containers.get(containerId), group,
                                        hostId)
            hostToAllocations.put(hostId, allocation)
            containerToHost.put(containerId, hostId)
            allocation
        }

        // Publish them
        newMappings foreach { m =>
            log.debug(m.toString)
            containerLocationSubject onNext m
        }
        true
    }
}
