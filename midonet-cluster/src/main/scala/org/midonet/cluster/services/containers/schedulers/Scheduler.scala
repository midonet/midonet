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
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

import com.google.inject.Inject
import com.typesafe.scalalogging.Logger
import org.slf4j.LoggerFactory
import org.slf4j.LoggerFactory.getLogger
import rx.schedulers.Schedulers
import rx.subjects.ReplaySubject
import rx.{Observable, Subscription}

import org.midonet.cluster.data.storage.{StateKey, StateStorage, Storage}
import org.midonet.cluster.models.State.ContainerStatus
import org.midonet.cluster.models.Topology.{Host, ServiceContainerGroup}
import org.midonet.cluster.services.{DeviceWatcher, MidonetBackend}
import org.midonet.cluster.util.UUIDUtil._
import org.midonet.cluster.util.logging.ProtoTextPrettifier._
import org.midonet.util.functors.{makeAction1, makeFunc1}

/**
  * A HostSelector trait that exposes the interface to be implemented by
  * specific allocation policies. It exposes: 1) a discardedHosts:[[rx.Observable]]
  * that notifies of hosts that are now being discarded as eligible; 2) a
  * candidateHosts:[[Future]] that returns (on completion) a [[Set]] of [[UUID]]
  * containing the current set of eligible hosts. With this information, the
  * scheduler will take the corresponding scheduling decisions.
  *
  * Implementations of the HostSelector trait should implement the
  * `onServiceContainerGroupUpdate` method if any action is required on the
  * host selector side when the associated service container group is updated.
  */
trait HostSelector {

    /**
      * Observable that emits events as soon as a host is no longer eligible.
      */
    val discardedHosts: Observable[UUID]

    /**
      * Current set of candidate hosts.
      */
    def candidateHosts: Future[Set[UUID]]

    /**
      * Specific handler called when the associated service container group
      * has changed for any reason.
      *
      * @param scg [[ServiceContainerGroup]] that was updated
      */
    def onServiceContainerGroupUpdate(scg: ServiceContainerGroup): Unit

}


/**
 * The type of notifications emitted by the scheduler. Change the container param
  * to a ServiceContainer (instead of UUID) once the container state monitoring
  * is in place.
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

/**
  * This service is in charge to schedule [[org.midonet.cluster.models.Topology.ServiceContainer]]
  * into eligible hosts specified by its associated [[ServiceContainerGroup]]
  * allocation policy. It also monitors when the eligible hosts becomes offline
  * for any reason to perform the necessary reallocation. It only notifies of
  * service containers to host mappings. In the event of a change in the
  * mapping, a new mapping is emited without specifying that the other one
  * is invalid. Consumers of this class should take care the removal of the
  * old actual allocation when a new one is detected.
  *
  * The current implementation is based on a round robin + failure detection
  * mechanism. This doesn't take into account the effect of churn on the
  * scheduling so other algorithms might be implemented in the future if necessary
  * (e.g. consistent hashing, load based, etc.).
  */
class Scheduler @Inject()(store: Storage,
                          stateStore: StateStorage,
                          executor: ExecutorService) {

    private val log = Logger(getLogger("org.midonet.cluster.containers.container-scheduler"))

    private val hostToContainers = mutable.HashMap.empty[UUID, mutable.Set[Created]]

    private def currentMappings: Set[Created] = hostToContainers.values.flatten.toSet

    private val pendingMappings = mutable.Set.empty[(ServiceContainerGroup, Set[UUID], Set[UUID])]

    private val hostToSelectors = new util.HashMap[UUID, mutable.Set[HostSelector]]

    private val hostToSubscriptions = new util.HashMap[UUID, Subscription]

    private val isHostAlive = new util.HashMap[UUID, Boolean]

    private val scgToSelector = new util.HashMap[UUID, HostSelector]()

    implicit val ec = ExecutionContext.fromExecutor(executor)

    private val scheduler = Schedulers.from(executor)

    private val anywhereHostSelector = new AnywhereHostSelector(store,
                                                                stateStore,
                                                                executor)(ec)

    private val scgWatcher = new DeviceWatcher[ServiceContainerGroup](
        store,
        onServiceContainerGroupUpdate,
        onServiceContainerGroupDelete,
        executor = executor)

    private val containerLocationSubject =
        ReplaySubject.create[ContainerEvent]

    /**
      * The service scheduler monitors any change on service container groups
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

    /**
      * Clear the state of this scheduler, unsubscribing from the service
      * container groups being monitored.
      */
    def stopScheduling(): Unit = {
        scgWatcher.unsubscribe()
    }

    private def onServiceContainerGroupDelete(scgId: Object): Unit = {
        log.debug(s"Service container group $scgId deleted.")
    }

    private def getHostSelector(group: ServiceContainerGroup): HostSelector = {
        var selector:HostSelector = null
        val portGroupId = if (group.hasPortGroupId) group.getPortGroupId.asJava else null
        val hostGroupId = if (group.hasHostGroupId) group.getHostGroupId.asJava else null
        log.debug(s"Service container group ${makeReadable(group.getId)} " +
                  s"schedules using port group $portGroupId and host " +
                  s"group $hostGroupId")
        (portGroupId, hostGroupId) match {
            case (null, null) => // anywhere selector
                selector = anywhereHostSelector
            case (uuid: UUID, null) => // Port group selector
                selector = scgToSelector
                    .getOrDefault(group.getId.asJava,
                                  new PortGroupHostSelector(store, stateStore, executor))
            case (null, uuid: UUID) => // Host group selector
                selector = scgToSelector
                    .getOrDefault(group.getId.asJava,
                                  new HostGroupHostSelector(store, stateStore, executor))
            case (uuid1: UUID, uuid2: UUID) => // not allowed
                log.debug(s"Service container group ${makeReadable(group.getId)} " +
                          "has both a port and ahost group specified (not allowed).")
            case _ =>
                // No previous selector created (null)
        }
        if (scgToSelector.containsKey(group.getId.asJava))
            log.debug(s"Reusing selector for serviceGroupId ${makeReadable(group.getId)}")
        else {
            log.debug(
                s"New selector for serviceGroupId ${makeReadable(group.getId)}")
            scgToSelector.put(group.getId.asJava, selector)
        }
        selector
    }


    /**
      * Method called by the device watcher whenever a [[ServiceContainerGroup]]
      * is updated. This method subscribes to changes on the discarded hosts
      * and candidate hosts of the specific host selector of this service
      * container group.
      */
    private def onServiceContainerGroupUpdate(group: ServiceContainerGroup): Unit = {
        log.debug(s"Updated container group ${makeReadable(group)}")
        val selector = getHostSelector(group)
        if (selector ne null) {
            selector.onServiceContainerGroupUpdate(group)
            // Subscribing to host no longer candidates
            selector.discardedHosts
                .observeOn(scheduler)
                .map[(UUID, UUID, HostSelector)] (makeFunc1(
                    h => (h, group.getId.asJava, selector)))
                .subscribe(makeAction1(onDiscardedHost))

            // Initial election of hosts
            selector.candidateHosts
                .onComplete {
                    case Success(hostSet) =>
                        onReceiveCandidateHosts(hostSet, selector, group)
                    case Failure(t) =>
                        log.error(s"Error on future $t")
                }
        }
    }

    private def onDiscardedHost(event: (UUID, UUID, HostSelector)): Unit = {
        event match {
            case (hostId, groupId, selector) =>
                // Delete host state subscription
                deleteHostStateSubscription(hostId, selector)
                // Update host mappings and reevaluate them
                val hostMappings = hostToContainers.get(hostId).get
                val oldMappings = hostMappings
                    .filter(chm => chm.group.getId.asJava == groupId)
                // Remove old mappings from internal state
                for (oldMapping <- oldMappings) {
                    hostMappings -= oldMapping
                }
                reevaluateMappings(oldMappings)
            case _ =>
        }
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
        log.debug(s"Host state change: Host $hostId is alive? $isAlive")
        isHostAlive.put(hostId, isAlive)
        if (!isAlive)
            log.debug("Host state is down")
        hostToContainers.get(hostId) match {
            case None =>
            case mappings => {
                // Remove old mappings from internal state
                hostToContainers.remove(hostId)
                reevaluateMappings(mappings.get)
            }
        }
        // In any case, reevaluate mappings in we have some of them in the queue
        reevaluatePendingMappings()
    }

    private def onReceiveCandidateHosts(hostSet: Set[UUID],
                                       selector: HostSelector,
                                       group: ServiceContainerGroup): Unit = {
        log.debug(s"Candidate hosts $hostSet for group ${makeReadable(group)} received from selector $selector.")
        // reevaluate subscriptions
        for (hostId <- hostSet.diff(hostToSubscriptions.keySet().asScala))
            addHostStateSubscriptions(hostId, selector)
        if (checkCandidatesReady(hostSet)) {
            createMappings(group, group.getServiceContainerIdsList.toSet, hostSet)
        }
        else {
            log.debug(s"There is no candidate host ready, enqueue for later evaluation.")
            // Enqueue for later
            pendingMappings.add((group, group.getServiceContainerIdsList.toSet, hostSet))
        }
    }

    /**
      * Add/delete methods to keep track of the subscriptions to interested
      * host selected by host selectors.
      */
    private def addHostStateSubscriptions(hostId: UUID, selector: HostSelector): Unit = {
        log.debug(s"Add host state subscription ${makeReadable(hostId)} from selector $selector")
        hostToSelectors
            .putIfAbsent(hostId, mutable.Set.empty[HostSelector])
        var selectors = hostToSelectors.get(hostId)

        selectors += selector
        val subscription = stateStore
            .keyObservable(hostId.toString,
                           classOf[Host],
                           hostId,
                           MidonetBackend.AliveKey)
            .observeOn(scheduler)
            .subscribe(makeAction1 [StateKey]{
                case state => onHostStateChange(hostId, state.nonEmpty)
            })
        hostToSubscriptions.put(hostId, subscription)
    }

    private def deleteHostStateSubscription(hostId: UUID, selector: HostSelector): Unit = {
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

    /**
      * Algorithm to select which containers go to which hosts. This implements
      * a basic round robin policy in case there are fewer hosts available than
      * containers. The load is also spread among eligible hosts instead of
      * scheduling all containers to the same host.
      */
    private def createMappings(group: ServiceContainerGroup, containers: Set[UUID], candidates: Set[UUID]): Unit = {
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
        log.debug(s"Reevaluate mappings $chms.")
        val groups = chms.map(chm => chm.group).toSet
        for (group <- groups) {
            log.debug(s"Getting selector ${makeReadable(group)}")
            val selector = scgToSelector.get(group.getId.asJava)
            selector.candidateHosts
                .onComplete {
                    case Success(hostSet) =>
                        log.debug("Before onReceiveCandidateHosts in the future")
                        onReceiveCandidateHosts(hostSet, selector, group)
                    case Failure(t) =>
                        log.error(s"Error on future $t")
                }
        }
    }

    /**
      * Called upon host state change. We need to make sure if this host allows
      * any of the pending mappings (without allocation) to be scheduled to a
      * host.
      */
    private def reevaluatePendingMappings(): Unit = {
        log.debug(s"Reevaluating pending mappings (after a host state update)")
        for (pending <- pendingMappings) {
            pending match {
                case (group:ServiceContainerGroup, containers:Set[UUID], candidates:Set[UUID]) =>
                    if (checkCandidatesReady(candidates)) {
                        createMappings(group, containers, candidates)
                        pendingMappings.remove(pending)
                    }
                case _ =>
            }
        }
    }

    /**
      * Checks that the set of candidate hosts is ready, meaning we already
      * have received the state of all of them, and that at least there is
      * one container alive.
      */
    private def checkCandidatesReady(candidates: Set[UUID]): Boolean = {
        log.debug(s"Checking the set of candidate hosts are ready $candidates")
        // check that we have an updated status for each candidate
        val statusReady = candidates
            .map(h => isHostAlive.containsKey(h))
            .forall(v => v)
        // check that at least one of them is alive
        val someAlive = candidates
            .map(h => isHostAlive.get(h))
            .reduce(_ || _)
        candidates.nonEmpty && statusReady && someAlive
    }


}


