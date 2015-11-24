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

import com.google.inject.Inject
import org.midonet.cluster.data.storage.{StateKey, StateStorage, Storage}
import org.midonet.cluster.models.Topology.{Port, ServiceContainer, ServiceContainerGroup}
import org.midonet.cluster.services.{DeviceWatcher, MidonetBackend}
import org.midonet.cluster.util.UUIDUtil._
import org.midonet.util.concurrent.toFutureOps
import org.midonet.util.functors.{makeAction1, makeFunc1}
import rx.subjects.PublishSubject

import scala.collection.mutable.ArrayBuffer
import scala.util.Random

abstract class AbstractScheduler @Inject()(store: Storage, stateStore: StateStorage) {

    case class Election(group: ServiceContainerGroup,
                       containers: List[ServiceContainer],
                       candidateHosts: List[UUID])

    case class ContainerHostMapping(container: ServiceContainer,
                                    group: ServiceContainerGroup,
                                    hostId: UUID,
                                    isAlive: Boolean)

    /*
    Observable to which the Container Manager service should subscribe. It will notify new
    scheduled (or rescheduled) containers with a ContainerHostMapping object.
     */
    protected val containerLocationSubject = PublishSubject.create[ContainerHostMapping]

    private val scgWatcher = new DeviceWatcher[ServiceContainerGroup](
        store,
        onServiceContainerGroupUpdate,
        Object => {},
        doAllocationPolicyFilter)

    protected def startScheduling(): PublishSubject[ContainerHostMapping] = {
        scgWatcher.subscribe()
        containerLocationSubject
    }

    protected def stopScheduling(): Unit = {
        scgWatcher.unsubscribe()
    }

    private val hostObservable = PublishSubject.create[ContainerHostMapping]
    hostObservable.subscribe(makeAction1[ContainerHostMapping] { chm =>
        if (chm.isAlive) {
            // Do something when a host update comes in
            onHostStateUpdate(chm.container, chm.group, chm.hostId)
        }
    })

    // Filter to apply to SCG to just receive notifications of those of interest for the sched
    protected def doAllocationPolicyFilter(scg: ServiceContainerGroup): Boolean

    // Each scheduler will return a list of candidates depending on different conditions
    protected def candidateHosts(scg: ServiceContainerGroup): List[UUID]

    private def onServiceContainerGroupUpdate(scg: ServiceContainerGroup): Unit = {
        val container_ids = scg.getServiceContainerIdsList.map { fromProto(_) }
        // check if the port is already in place for a container (port binding)
        val containers = store.getAll(classOf[ServiceContainer], container_ids).await().toList
        // candidates from each specific scheduling policy
        val candidates = candidateHosts(scg)
        // Create backrefs for easy reference later
        val containersMap = containers.map(c => c.getId -> c).toMap
        val candidatesSet = candidates.toSet

        val nonScheduledContainers = containers.filter(sc => {Option(sc.getPortId).isEmpty}).toList
        val resultsNotYetScheduled = celebrate(new Election(scg,
                                             nonScheduledContainers,
                                             candidates))
        // check that scheduled containers are running on candidate hosts
        val needsRescheduling = store
                .getAll(classOf[Port], containers
                        .filter(sc => Option(sc.getPortId).nonEmpty)
                        .map(sc => sc.getPortId))
                .await()
                .map(p => (p.getHostId, p.getServiceContainerId))
                .filter(info => !candidatesSet.contains(info._1))
                .map(info => containersMap.get(info._2).get).toList
        val resultsRescheduling = celebrate(new Election(scg,
                                                         needsRescheduling,
                                                         candidates))

        resultsNotYetScheduled ++ resultsRescheduling foreach applyContainerHostMapping

    }

    private def onHostStateUpdate(sc: ServiceContainer, scg: ServiceContainerGroup, hostId: UUID): Unit = {
        // host went down, pick a new candidate
        val candidate_id = Random.shuffle(candidateHosts(scg)).head
        // emit the new mapping
        applyContainerHostMapping(new ContainerHostMapping(sc, scg, candidate_id, false))
    }

    /* We do a Round robin selection between the containers that need scheduling
     * and the available hosts just in case there are fewer hosts than containers.
     * The load is also spread among elegible hosts instead of scheduling all
     * containers in the same host. Returns a List of tupples (SCG.id, Host.id) */
    private def celebrate(election: Election): List[ContainerHostMapping] = {
        val candidateIt = Iterator.continually(election.candidateHosts).flatten
        val containerIt = election.containers.iterator
        val results = new ArrayBuffer[ContainerHostMapping]()
        while (containerIt.hasNext) {
            results.append(new ContainerHostMapping(containerIt.next(), election.group, candidateIt.next(), false))
        }
        results.toList
    }

    private def applyContainerHostMapping(mapping: ContainerHostMapping): Unit = {
        containerLocationSubject onNext mapping
        stateStore.keyObservable(mapping.hostId.toString, classOf[StateKey], mapping.hostId, MidonetBackend.AliveKey)
                  .map[ContainerHostMapping](makeFunc1[StateKey, ContainerHostMapping](state => new ContainerHostMapping(mapping.container,
                                                                   mapping.group,
                                                                   mapping.hostId,
                                                                   state.nonEmpty)))
                  .subscribe(hostObservable)
    }

}
