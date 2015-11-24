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
import java.util.concurrent.{ConcurrentHashMap, Executors}

import com.google.common.base.MoreObjects
import com.google.inject.Inject
import org.midonet.cluster.data.storage.{StateKey, StateStorage, Storage}
import org.midonet.cluster.models.Commons
import org.midonet.cluster.models.Topology.{Host, ServiceContainerGroup}
import org.midonet.cluster.services.{DeviceStateWatcher, MidonetBackend, DeviceWatcher}
import org.midonet.cluster.util.logging.ProtoTextPrettifier._
import org.midonet.cluster.util.UUIDUtil._
import org.midonet.util.functors.makeFunc1
import org.slf4j.LoggerFactory
import rx.Observable
import rx.subjects.PublishSubject

import scala.collection.JavaConversions._
import scala.concurrent.{ExecutionContext, Future}

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

    val discardedHosts: Observable[UUID]

    val candidateHosts: Future[Set[UUID]]

    /**
      * Specific handler called when the associated service container group
      * has changed for any reason.
      *
      * @param scg [[ServiceContainerGroup]] that was updated
      */
    def onServiceContainerGroupUpdate(scg: ServiceContainerGroup): Unit

}


/**
  * Interface for the container manager service. This class is used to emit the
  * scheduling decisions through the [[Scheduler]] observable.
  *
  * @param container [[UUID]] service container id being scheduled
  * @param group [[ServiceContainerGroup]] Service container group the
  *             `container` belongs to.
  * @param hostId [[UUID]] host id of the host where this `container` is being
  *              scheduled.
  */
case class ContainerHostMapping(container: UUID,
                                group: ServiceContainerGroup,
                                hostId: UUID) {
    override val toString =
        MoreObjects.toStringHelper(this).omitNullValues()
                .add("serviceContainerId", container)
                .add("serviceContainerGroupId", group.getId)
                .add("hostId", hostId)
}

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
                          stateStore: StateStorage) {

    case class Election(group: ServiceContainerGroup,
                       containers: util.ArrayList[UUID],
                       candidateHosts: Set[UUID])

    private val log =
        LoggerFactory.getLogger("org.midonet.cluster.containers.scheduler")

    private val hostContainerMappings =
        new ConcurrentHashMap[UUID, List[ContainerHostMapping]]()

    private val scgToSelector = new ConcurrentHashMap[UUID, HostSelector]()

    implicit val ec =
        ExecutionContext.fromExecutor(Executors.newSingleThreadExecutor())

    private val hostStateWatcher = new DeviceStateWatcher[Host](
        stateStore,
        onHostStateChange)

    private val scgWatcher = new DeviceWatcher[ServiceContainerGroup](
        store,
        onServiceContainerGroupUpdate,
        onServiceContainerGroupDelete)

    private val containerLocationSubject =
        PublishSubject.create[ContainerHostMapping]

    /**
      * The service scheduler monitors any change on service container groups
      * created, updated and deleted to allocate containers to the appropriate
      * hosts according to the specified policy in the container group. This
      * method starts monitoring container groups and emits
      * [[ContainerHostMapping]] events through the observable returned.
      *
      * @return [[rx.Observable]][[ContainerHostMapping]] Observable where
      *        the scheduler emits the mappings of containers to host.
      */
    protected def startScheduling(): Observable[ContainerHostMapping] = {
        scgWatcher.subscribe()
        containerLocationSubject.asObservable()
    }

    /**
      * Clear the state of this scheduler, unsubscribing from the service
      * container groups being monitored.
      */
    protected def stopScheduling(): Unit = {
        scgWatcher.unsubscribe()
    }

    private def onServiceContainerGroupDelete(scgId: Object): Unit = {
        log.debug("Service container group $scgId deleted.")
    }

    private def getHostSelector(scg: ServiceContainerGroup): HostSelector = {
        var selector: HostSelector = null
        (scg.getPortGroupId, scg.getHostGroupId) match {
            case (null, null) => // Anywhere selector
                log.debug("Creating an AnywhereHostSelector for container " +
                          s"group ${makeReadable(scg.getId)}")
                selector = AnywhereHostSelector
            case (Commons.UUID, null) => // Port group selector
                log.debug("Creating a PortGroupHostSelector for container " +
                          s"group ${makeReadable(scg.getId)}")
                selector = new PortGroupHostSelector(store, stateStore)
            case (null, Commons.UUID) => // Host group selector
                log.debug("Creating a HostGroupHostSelector for container " +
                          s"group ${makeReadable(scg.getId)}")
                selector = new HostGroupHostSelector(store, stateStore)
            case (Commons.UUID, Commons.UUID) => // not allowed
                log.debug(s"Service container group ${makeReadable(scg.getId)} " +
                          "have a port and and host group specified (not allowed).")
        }
        selector
    }

    /**
      * Method called by the device watcher whenever a [[ServiceContainerGroup]]
      * is updated. This method subscribes to changes on the discarded hosts
      * and candidate hosts of the specific host selector of this service
      * container group.
      */
    private def onServiceContainerGroupUpdate(scg: ServiceContainerGroup): Unit = {
        var selector = scgToSelector.get(scg.getId)
        if (selector eq null) {
            selector = getHostSelector(scg)
            selector match {
                case null =>
                    // No valid selector, skip scheduling
                    log.warn(s"Service container group ${makeReadable(scg.getId)} " +
                            "does not have a valid host selector. Skip scheduling.")
                    return
            }
            scgToSelector.put(fromProto(scg.getId), selector)
            // Subscribing to host no longer candidates
            selector.discardedHosts
                    .map { case h:UUID => // unsubscribe from host notifications
                        hostStateWatcher.unsubscribe(
                            h.toString, h, MidonetBackend.AliveKey)
                    }
                    .map[ContainerHostMapping] { case h =>
                            hostContainerMappings.get(h)
                    }
                    // Get only those chm belonging to this scg
                    .filter(makeFunc1(chm => chm.group.getId == scg.getId))
                    .map { case chml =>
                        applyMappings(reevaluateMappings(chml))
                    }

            // Initial election of hosts
            selector.candidateHosts
                    .map {
                        case s => s.map {
                            case h => hostStateWatcher.subscribe(
                                h.toString, h, MidonetBackend.AliveKey)
                        }
                    }
                    .onSuccess {
                        case hostSet  =>
                            // Create and apply mappings (blocking on isHostAlive)
                            applyMappings(
                                createMappings(new Election(
                                    scg,
                                    scg.getServiceContainerIdsList,
                                    hostSet.filter(isHostAlive))))
            }
        }
        selector.onServiceContainerGroupUpdate(scg)
    }

    /**
      * Helper method to publish the container to host mappings to the observer
      *
      * @param mappings [[util.List]][[ContainerHostMapping]] list of container
      *                host mappings that will be notified.
      */
    private def applyMappings(mappings: util.List[ContainerHostMapping]): Unit = {
        mappings foreach {
            case mapping =>
                containerLocationSubject.onNext(mapping)
                log.debug(s"Emitting mapping $mapping")
        }
    }

    /**
      * Handler method to execute when the hostStateWatcher detects a change on
      * the specified host. If the state is empty, the host is down and any
      * service container running on it should be rescheduled on other hosts.
      * If the state is not empty (meaning the host just became alive), we do
      * nothing to avoid churn.
      */
    private def onHostStateChange(namespace: String, hostId: UUID, key: String, state: StateKey): Unit = {
        if (state.isEmpty)
            applyMappings(reevaluateMappings(hostContainerMappings.get(hostId)))
    }

    /**
      * Reschedules containers from a no longer candidate host. Called when:
      * 1) the host selector notifies this host is not a candidate anymore.
      * 2) the host state watcher detects that a host has failed.
      *
      * @param chml [[util.List]] The [[ContainerHostMapping]] list of the
      *            containers running on a given host
      * @return [[util.List]][[ContainerHostMapping]]
      */
    private def reevaluateMappings(chml: util.List[ContainerHostMapping]): util.List[ContainerHostMapping] = {
        val chmByGroupId = new ConcurrentHashMap[ServiceContainerGroup, util.ArrayList[UUID]]()
        val results = new util.ArrayList[ContainerHostMapping]()
        // Organize the containers to reschedule by SCG so we pick its correct selector
        for (chm <- chml) {
            val chmList = chmByGroupId.putIfAbsent(chm.group, new util.ArrayList[UUID]())
            chmList.add(chm.container)
        }
        // Reschedule container to another candidate hosts
        for ((group, containers) <- chmByGroupId) {
            val selector = scgToSelector.get(group.getId)
            selector.candidateHosts
                    .map {
                        case s => s.map {
                            case h => hostStateWatcher.subscribe(
                                h.toString, h, MidonetBackend.AliveKey)
                        }
                    }
                    .onSuccess {
                        case hostSet =>
                            results ++= createMappings(
                                new Election(
                                    group,
                                    containers,
                                    hostSet.filter(isHostAlive)))
                    }
        }
        results
    }

    /**
      * Algorithm to select which containers go to which hosts. This implements
      * a basic round robin policy in case there are fewer hosts available than
      * containers. The load is also spread among eligible hosts instead of
      * scheduling all containers to the same host.
      *
      * @param election [[Election]] a set of candidates hosts and containers
      *                to be mapped together.
      * @return [[util.List]][[ContainerHostMapping]]
      */
    private def createMappings(election: Election): util.List[ContainerHostMapping] = {
        // Pick only alive hosts
        val candidateIt = Iterator.continually(election.candidateHosts).flatten
        val containerIt = election.containers.iterator
        val results = new util.ArrayList[ContainerHostMapping]()
        while (containerIt.hasNext) {
            results.append(new ContainerHostMapping(
                containerIt.next(), election.group, candidateIt.next()))
        }
        if (results.isEmpty) {
            log.warn("No candidates alive to allocation the list of containers " +
                    "$election.containers")
        }
        results
    }


    /**
      * Queries the state of the host from a key observable registered on
      * the host state watcher. This method blocks until the first notification
      * from the observable arrives.
      *
      * @param hostId [[UUID]] host id
      * @return [[Boolean]] whether the host is alive
      */
    private def isHostAlive(hostId: UUID): Boolean = {
        hostStateWatcher
                .get(hostId.toString, hostId, MidonetBackend.AliveKey)
                .nonEmpty
    }


}


