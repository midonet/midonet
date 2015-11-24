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

package org.midonet.cluster.services.conman.schedulers

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

import com.google.inject.Inject
import org.eclipse.jetty.util.ConcurrentHashSet
import org.midonet.cluster.data.storage.{StateKey, SingleValueKey, StateStorage, Storage}
import org.midonet.cluster.models.Services.{ServiceContainer, ServiceContainerGroup}
import org.midonet.cluster.models.State.{ContainerAgent, HostState}
import org.midonet.cluster.models.Topology
import org.midonet.cluster.models.Topology.{HostGroup, Host, Port, PortGroup}
import org.midonet.cluster.services.{MidonetBackend, DeviceWatcher}
import org.midonet.cluster.util.UUIDUtil._
import org.midonet.util.concurrent.toFutureOps
import org.midonet.util.functors._
import rx.Observable
import rx.subjects.PublishSubject
import sun.font.TrueTypeFont

import scala.collection.mutable.ArrayBuffer
import scala.util.Random

class AnywhereScheduler @Inject()(store: Storage, stateStore: StateStorage) {

    abstract class Election {
        def containers: List[ServiceContainer]
        def candidateHosts: List[UUID]
    }

    abstract class ElectionResult {
        def container: ServiceContainer
        def hostId: UUID
    }

    // TODO: This is generic and can be refactored to be reused by other schedulers
    private val scgWatcher = new DeviceWatcher[ServiceContainerGroup](store,
                                                                      onServiceContainerGroupUpdate,
                                                                      onServiceContainerGroupDelete,
                                                                      makeFunc1(doAllocationPolicyFilter))
    scgWatcher.startWatching()

    // Watcher for hosts where we have SC deployed
    private val hostBackrefs = new ConcurrentHashMap[StateKey, ServiceContainer]
    private val hostInterestList = PublishSubject.create[StateKey]
    hostInterestList.subscribe(makeAction1[StateKey] { hostStateKey: StateKey =>
        val relatedSC = hostBackrefs.get(hostStateKey)
        if (Option(relatedSC).nonEmpty) {
            // Do something when a host update comes in
            onHostStateUpdate(relatedSC, hostStateKey)
        }
        else {
            // Not sure that's necessary?
            hostBackrefs.remove(hostStateKey)
        }
    })

    def doAllocationPolicyFilter(sgc: ServiceContainerGroup): Boolean = {
        Option(sgc.getHostGroupId).isEmpty && Option(sgc.getPortGroupId).isEmpty
    }

    // TODO generic
    private def onServiceContainerGroupUpdate(scg: ServiceContainerGroup): Unit = {
        val containers = scg.getServiceContainerIdsList.map { fromProto(_) }
        // check if the service containers are already scheduled (ContainerAgent in the state store)
        // TODO: probably can be done in a better way?
        val nonScheduledContainers = containers
                .map(c => Pair(c, stateStore.getKey(classOf[ContainerAgent], c, MidonetBackend.ContainerLocation)))
                .map(pair => Pair(pair._1, pair._2.toBlocking.single()))
                .filter(pair => pair._2.isEmpty)
                .map(pair => pair._1)

        store.getAll(classOf[ServiceContainer], nonScheduledContainers)
             .await()

        val candidates = candidateHosts()
        val results = celebrate(new Election {
            override def containers: List[ServiceContainer] = store
                    .getAll(classOf[ServiceContainer], nonScheduledContainers).await().toList
            override def candidateHosts: List[UUID] = candidates
        })

        results foreach applyContainerHostMapping
    }

    // TODO generic. Not sure what should be done here
    def onServiceContainerGroupDelete(id: Object): Unit = ???

    private def onHostStateUpdate(sc: ServiceContainer, hostStateKey: StateKey): Unit = {
        // if host is down, schedule service container referenced to another host
        if (hostStateKey.isEmpty) {
            // host went down, pick a new candidate
            val candidate_id = Random.shuffle(candidateHosts()).head
            // emit the new mapping
            applyContainerHostMapping(new ElectionResult {
                override def container: ServiceContainer = sc
                override def hostId: UUID = candidate_id
            })
        }
    }

    private def applyContainerHostMapping(scm: ElectionResult): Unit = {
        stateStore.addValue(classOf[ServiceContainer],
            scm.container.getId,
            MidonetBackend.ContainerLocation,
            ContainerAgent.newBuilder()
                    .setHostId(scm.hostId)
                    .setContainerId(scm.container.getId)
                    .build().toString())
        // Add the host to the interest list
        stateStore.keyObservable(classOf[Host], scm.hostId, MidonetBackend.AliveKey).subscribe(hostInterestList)
    }

    // Return only alive hosts
    private def candidateHosts(): List[UUID] = {
        // FIXME TODO: it's blocking for each host request, probably a better way
        store.getAll(classOf[Host])
                .await()
                .filter(h => stateStore.getKey(classOf[HostState], h.getId, MidonetBackend.AliveKey)
                                       .toBlocking
                                       .single()
                                       .nonEmpty)
                .map(h => fromProto(h.getId))
                .toList
    }

    /* We do a Round robin selection between the containers that need scheduling
     * and the available hosts just in case there are fewer hosts than containers.
     * The load is also spread among elegible hosts instead of scheduling all
     * containers in the same host. Returns a List of tupples (SCG.id, Host.id) */
    private def celebrate(election: Election): List[ElectionResult] = {
        val candidateIt = Iterator.continually(election.candidateHosts).flatten
        val containerIt = election.containers.iterator
        val results = new ArrayBuffer[ElectionResult]()
        while (containerIt.hasNext) {
            results.append(new ElectionResult {
                override def container: ServiceContainer = containerIt.next()
                override def hostId: UUID = candidateIt.next()
            })
        }
        results.toList
    }
}
