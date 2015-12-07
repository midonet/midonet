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
import java.util.concurrent.ExecutorService

import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.concurrent.ExecutionContext
import scala.util.{Failure, Success}

import com.google.inject.Inject
import com.typesafe.scalalogging.Logger
import org.slf4j.LoggerFactory.getLogger
import rx.schedulers.Schedulers
import rx.subjects.ReplaySubject
import rx.{Observable, Subscription}

import org.midonet.cluster.data.storage.{StateKey, StateStorage, Storage}
import org.midonet.cluster.models.Commons
import org.midonet.cluster.models.State.ContainerStatus
import org.midonet.cluster.models.Topology.{Host, ServiceContainerGroup}
import org.midonet.cluster.services.DeviceWatcher
import org.midonet.cluster.services.MidonetBackend.AliveKey
import org.midonet.cluster.util.UUIDUtil._
import org.midonet.cluster.util.logging.ProtoTextPrettifier._
import org.midonet.util.functors.{makeAction1, makeFunc1}




/** The type of notifications emitted by the scheduler. Change the container
  * param to a ServiceContainer (instead of UUID) once the container state
  * monitoring is in place.
  */
trait ContainerEvent
case class Created(container: UUID, group: ServiceContainerGroup,
                   hostId: UUID) extends ContainerEvent
case class Up(container: UUID, group: ServiceContainerGroup,
              hostId: UUID) extends ContainerEvent
case class Down(container: UUID, group: ServiceContainerGroup,
                hostId: UUID, status: ContainerStatus)
case class Deleted(container: UUID, group: ServiceContainerGroup,
                   hostId: UUID)

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

    /* Used to retrieve the allocation strategy for each container */
    private val selectorFactory = new HostSelectorFactory(store, executor)

    /* Containers indexed by the host they are allocated to */
    private val hostToContainers = mutable.HashMap.empty[UUID, mutable.Set[Created]]

    private val hostToSelectors = new util.HashMap[UUID, mutable.Set[HostSelector]]

    /* The subscription to each host */
    private val hostToSubscriptions = new util.HashMap[UUID, Subscription]

    private val pendingMappings = mutable.Set.empty[(ServiceContainerGroup, Set[UUID], Set[UUID])]

    private val isHostAlive = new util.HashMap[UUID, Boolean]

    private val scgToSelector = new util.HashMap[UUID, HostSelector]()

    private val scgWatcher = new DeviceWatcher[ServiceContainerGroup](
        store,
        onContainerGroupUpdate,
        onContainerGroupDelete,
        filterHandler = _ => true,
        executor)

    /** TODO FIXME: don't do a Replay here, or we'll buffer overflow */
    private val containerLocationSubject = ReplaySubject.create[ContainerEvent]

    private def currentMappings: Set[Created] = hostToContainers.values.flatten.toSet

    /** The service scheduler monitors any change on service container groups
      * created, updated and deleted to allocate containers to the appropriate
      * hosts according to the specified policy in the container group. This
      * method starts monitoring container groups and emits
      * [[Created]] events through the observable returned.
      *
      * @return [[rx.Observable]][[Created]] Observable where
      *         the scheduler emits the mappings of containers to host.
      */
    def startScheduling(): Observable[ContainerEvent] = {
        // TODO FIXME: get the initial state in case there was a cluster restart
        scgWatcher.subscribe()
        containerLocationSubject.observeOn(scheduler)
    }

    /** Clear the state of this scheduler, unsubscribing from the service
      * container groups being monitored.
      */
    def stopScheduling(): Unit = scgWatcher.unsubscribe()

    private def onContainerGroupDelete(scgId: Object): Unit = scgId match {
        case id: Commons.UUID =>
            log.debug(s"Container group ${makeReadable(id)} deleted.")
        case _ =>
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
        val gId = group.getId.asJava
        if (scgToSelector.containsKey(gId)) {
            return // already known, we don't care about updates
        }

        log.debug(s"New container group $gId")
        val selector = selectorFactory.getHostSelector (group)
        scgToSelector.put(gId, selector)

        selector.discardedHosts
                .observeOn(scheduler)
                .map[(UUID, UUID)](makeFunc1((_, gId)))
                .subscribe(makeAction1(onDiscardedHost))

        allocateHosts(group)
    }

    /** Triggered whenever a host previously reported as eligible for a
      * Container group is no longer eligible.
      */
    private def onDiscardedHost(event: (UUID, UUID)): Unit = event match {
        case (hostId, groupId) =>
            val selector = scgToSelector.get(groupId)
            deleteHostStateSubscription(hostId, selector)

            val hostMappings = hostToContainers.get(hostId).get
            val oldMappings = hostMappings.filter { _.group.getId.asJava == groupId }
            hostMappings --= oldMappings
            reevaluateMappings(oldMappings)
        case _ =>
    }

    /**
      * Handler method to execute when the hostStateWatcher detects a change on
      * the specified host. If the host is down, any service container
      * running on it should be rescheduled on other hosts.
      * If the host became alive, we reevaluate the mappings in case there
      * were containers whose eligible hosts were down at a prior scheduling
      * time.
      */
    private def onHostStateChange(hostId: UUID, isAlive: Boolean): Unit = {
        // reevaluate mappings in either case, because there may be some
        // containers without mappings because their eligible hosts
        // be down
        log.debug(s"Host $hostId state change, alive: $isAlive")
        isHostAlive.put(hostId, isAlive)
        hostToContainers.get(hostId).foreach { mappings =>
            hostToContainers.remove(hostId)
            reevaluateMappings(mappings)
        }
        // In any case, reevaluate mappings in we have some of them in the queue
        reevaluatePendingMappings()
    }

    /**
      * Add/delete methods to keep track of the subscriptions to interested
      * host selected by host selectors.
      */
    private def addHostStateSubscriptions(hostId: UUID, selector: HostSelector): Unit = {
        log.debug(s"Add host state subscription ${makeReadable(hostId)} from " +
                  s"selector $selector")
        hostToSelectors.putIfAbsent(hostId, mutable.Set.empty[HostSelector])
        var selectors = hostToSelectors.get(hostId)
        selectors += selector
        val subscription = stateStore.keyObservable(hostId.toString,
                                                    classOf[Host],
                                                    hostId, AliveKey)
            .observeOn(scheduler)
            .subscribe(makeAction1 [StateKey]{
                case state => onHostStateChange(hostId, state.nonEmpty)
            })
        hostToSubscriptions.put(hostId, subscription)
    }

    /** Stops watching for the state of the given host */
    private def deleteHostStateSubscription(hostId: UUID,
                                            selector: HostSelector): Unit = {
        log.debug(s"Delete host state subscription ${makeReadable(hostId)} from selector $selector")
        val selectors = hostToSelectors.get(hostId)
        selectors -= selector
        if (selectors.isEmpty) {
            log.debug(s"No selectors interested in this host, delete it.")
            hostToSubscriptions.get(hostId).unsubscribe()
            hostToSelectors.remove(hostId)
        }
        else
            log.debug(s"There are still selectors interested in this host, not delete.")
    }

    /** Algorithm to select which containers go to which hosts. This implements
      * a basic round robin policy in case there are fewer hosts available than
      * containers. The load is also spread among eligible hosts instead of
      * scheduling all containers to the same host.
      */
    private def createMappings(group: ServiceContainerGroup,
                               containers: Set[UUID],
                               candidates: Set[UUID]): Unit = {
        log.debug(s"Candidates ready, create pending mappings and notify.")
        // Skip containers that already have a mapping with the current set
        // of candidates
        val pendingContainers = containers
            .diff(currentMappings
                      .filter(mapping => {
                          containers.contains(mapping.container) &&
                          candidates.contains(mapping.hostId)
                      })
                      .map(mapping => mapping.container))

        // Only consider those hosts that are alive
        val aliveCandidates = candidates.filter(h => isHostAlive.get(h))

        if (aliveCandidates.isEmpty) {
            log.warn(s"No candidates eligible for the list of containers " +
                     s"$containers")
        }
        else {
            val candidateIt = Iterator
                .continually(aliveCandidates)
                .take(pendingContainers.size / aliveCandidates.size + 1)
                .flatten
            val mappings = for (container <- pendingContainers) yield {
                new Created(container, group, candidateIt.next())
            }

            for (mapping <- mappings) {
                log.debug(s"Emitting mapping $mapping")
                // Update the current mapping list of the host
                val currentMappings = hostToContainers
                    .getOrElseUpdate(mapping.hostId,
                                     mutable.HashSet.empty[Created])
                currentMappings += mapping
                containerLocationSubject.onNext(mapping)
            }
        }
    }

    /**
      * Reschedules containers from a no longer candidate host. Called when:
      * 1) the host selector notifies this host is not a candidate anymore.
      * 2) the host state watcher detects that a host has failed.
      *
      * @param chms [[mutable.Set]] The [[Created]] set of the
      *             containers that should be reescheduled
      * @return [[util.List]][[Created]]
      */
    private def reevaluateMappings(chms: mutable.Set[Created]): Unit = {
        chms.map { _.group } foreach allocateHosts
    }

    /** Takes all the containers in the given group, and allocates them to an
      * eligible host.  Note that this operation may complete asynchronously
      * if the list of eligible hosts for this group is not
      * immediately available.
      */
    private def allocateHosts(group: ServiceContainerGroup): Unit = {
        val scgId = group.getId.asJava
        log.debug(s"Evaluating host allocation for containers in group $scgId")

        val selector = scgToSelector.get(scgId)
        if (selector eq null) {
            log.info(s"Unexpected: group $scgId has no host selector!")
        }

        selector.candidateHosts.onComplete {
           case Success(hostSet) =>
               log.debug(s"Candidate hosts for group $scgId: $hostSet")
               // reevaluate subscriptions
               for (hostId <- hostSet.diff(hostToSubscriptions.keySet().asScala))
                   addHostStateSubscriptions(hostId, selector)

               val containerIds= group.getServiceContainerIdsList.toSet
               if (checkCandidatesReady(hostSet)) {
                   createMappings(group, containerIds, hostSet)
               } else {
                   log.debug(s"There is no candidate host ready, enqueue for later evaluation.")
                   pendingMappings.add((group, containerIds, hostSet))
               }
           case Failure(t) =>
               log.warn("Failed to fetch candidate hosts for container " +
                        s"group $scgId")
       }
    }

    /** Called upon host state change. We need to make sure if this host allows
      * any of the pending mappings (without allocation) to be scheduled to a
      * host.
      */
    private def reevaluatePendingMappings(): Unit = {
        log.debug("Reevaluating pending mappings (after a host state update)")
        pendingMappings foreach {
            case pending @ (group, containers, candidates) =>
                if (checkCandidatesReady(candidates)) {
                    createMappings(group, containers, candidates)
                    pendingMappings.remove(pending)
                }
            case _ =>
        }
    }

    /** Checks that the set of candidate hosts is ready, meaning we already
      * have received the state of all of them, and that at least there is
      * one container alive.
      */
    private def checkCandidatesReady(candidates: Set[UUID]): Boolean = {
        log.debug(s"Checking the set of candidate hosts are ready $candidates")
        // check that we have an updated status for each candidate
        val statusReady = candidates.map(h => isHostAlive.containsKey(h))
                                    .forall(v => v)
        // check that at least one of them is alive
        val someAlive = candidates.map(h => isHostAlive.get(h))
                                  .reduce(_ || _)

        candidates.nonEmpty && statusReady && someAlive
    }
}
