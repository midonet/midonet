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
import org.midonet.cluster.data.storage.{StateStorage, Storage}
import org.midonet.cluster.models.Topology.{Host, ServiceContainerGroup}
import org.midonet.cluster.services.DeviceWatcher
import org.midonet.cluster.util.UUIDUtil._
import org.midonet.util.functors.{makeAction1, makeFunc1}
import org.slf4j.LoggerFactory
import rx.Observable
import rx.subjects.PublishSubject

import scala.collection.JavaConversions._
import scala.concurrent.{ExecutionContext, Future}

trait HostSelector{
    val discardedHosts: Observable[UUID]
    val candidateHosts: Future[Set[UUID]]
}

abstract class AbstractScheduler @Inject()(store: Storage,
                                           stateStore: StateStorage,
                                           scgFilter: ServiceContainerGroup => java.lang.Boolean) {

    case class Election(group: ServiceContainerGroup,
                       containers: util.ArrayList[UUID],
                       candidateHosts: Set[UUID])

    case class ContainerHostMapping(container: UUID,
                                    group: ServiceContainerGroup,
                                    hostId: UUID) {
        override val toString =
            MoreObjects.toStringHelper(this).omitNullValues()
                .add("serviceContainerId", container)
                .add("serviceContainerGroupId", group.getId)
                .add("hostId", hostId)
    }


    private val log = LoggerFactory.getLogger("org.midonet.cluster.containers.scheduler")

    val hostContainerMappings = new ConcurrentHashMap[UUID, List[ContainerHostMapping]]()

    val scgToSelectorMap = new ConcurrentHashMap[UUID, HostSelector]()

    implicit val ec = ExecutionContext.fromExecutor(Executors.newSingleThreadExecutor())

    /*
    Observable to which the Container Manager service should subscribe. It will notify new
    scheduled (or rescheduled) containers with a ContainerHostMapping object.
     */
    protected val containerLocationSubject = PublishSubject.create[ContainerHostMapping]

    private val scgWatcher = new DeviceWatcher[ServiceContainerGroup](
        store,
        onServiceContainerGroupUpdate,
        onServiceContainerGroupDelete,
        scgFilter)


    // Do it after subsribing to containerMappings
    protected def startScheduling(): Unit = {
        scgWatcher.subscribe()
    }

    protected def stopScheduling(): Unit = {
        scgWatcher.unsubscribe()
    }

    protected def onServiceContainerGroupDelete(scgId: Object): Unit = ???

    private def getHostSelector(scg: ServiceContainerGroup): HostSelector = {
        var selector: HostSelector
        (scg.getPortGroupId, scg.getHostGroupId) match {
            case (null, null) => // Anywhere selector
                log.debug("Creating an AnywhereHostSelector for container " +
                          "group $scg.getId")
                selector = new AnywhereHostSelector(store, stateStore, scg)
            case (Some, null) => // Port group selector
                log.debug("Creating a PortGroupHostSelector for container " +
                        "group $scg.getId")
                selector = new PortGroupHostScheduler(store, stateStore, scg)
            case (null, Some) => // Host group selector
                log.debug("Creating a HostGroupHostSelector for container " +
                        "group $scg.getId")
                selector = new HostGroupHostScheduler(store, stateStore, scg)
        }
        selector
    }

    protected def onServiceContainerGroupUpdate(scg: ServiceContainerGroup): Unit = {
        var selector = scgToSelectorMap.get(scg.getId)
        if (selector eq null) {
            selector = getHostSelector(scg)
            scgToSelectorMap.put(fromProto(scg.getId), selector)
            // Subscribing to host no longer candidates
            selector.discardedHosts
                    .map(makeFunc1(h => hostContainerMappings.get(h)))
                    .map(makeFunc1(l => reevaluateMappings(l)))
                    // TODO FIXME: move makeAction to function
                    .subscribe(makeAction1 {
                        mappings: List[ContainerHostMapping] => {
                            mappings foreach containerLocationSubject.onNext
                        }})

            // Initial election of hosts
            selector.candidateHosts
                    .map { hostList =>
                        celebrate(new Election(scg,
                            scg.getServiceContainerIdsList,
                            hostList))
                    }.onSuccess { case l => // TODO FIXME: use the same function as above
                        l.foreach(containerLocationSubject.onNext)
                    }
        }
    }

    // A host failed, we need to reschedule the containers allocated to it
    private def reevaluateMappings(chml: List[ContainerHostMapping]): List[ContainerHostMapping] = {
        val chmByGroupId = new ConcurrentHashMap[ServiceContainerGroup, util.ArrayList[UUID]]
        val results = List.empty[ContainerHostMapping]
        // Organize the containers to reschedule by SCG so we pick its correct selector
        for (chm <- chml) {
            val chmlist = chmByGroupId.putIfAbsent(chm.group, new util.ArrayList[UUID]())
            chmlist.add(chm.container)
        }
        // Reschedule container to another candidates hosts
        for ((group, containers) <- chmByGroupId) {
            val selector = scgToSelectorMap.get(group.getId)
            val candidates = selector.candidateHosts.onSuccess {
                case l =>
                    results.addAll(celebrate(new Election(group, containers, l)))
            }
        }
        results.toList
    }

    /* We do a Round robin selection between the containers that need scheduling
     * and the available hosts just in case there are fewer hosts than containers.
     * The load is also spread among elegible hosts instead of scheduling all
     * containers in the same host. Returns a List of ContainerHostMapping objects */
    private def celebrate(election: Election): util.List[ContainerHostMapping] = {
        val candidateIt = Iterator.continually(election.candidateHosts).flatten
        val containerIt = election.containers.iterator
        val results = new util.ArrayList[ContainerHostMapping]()
        while (containerIt.hasNext) {
            results.append(new ContainerHostMapping(containerIt.next(), election.group, candidateIt.next()))
        }
        results
    }

}


