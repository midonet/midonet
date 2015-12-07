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
import java.util.concurrent.ExecutorService

import org.midonet.cluster.data.storage.{StateStorage, Storage}
import org.midonet.cluster.models.Commons
import org.midonet.cluster.models.Topology.{HostGroup, ServiceContainerGroup}
import org.midonet.cluster.services.DeviceWatcher
import org.midonet.cluster.util.UUIDUtil._
import org.midonet.cluster.util.logging.ProtoTextPrettifier._
import org.slf4j.LoggerFactory
import rx.Observable
import rx.subjects.PublishSubject

import scala.concurrent.{ExecutionContext, Future, Promise}

/**
  * This class implements the [[HostSelector]] trait. A host is eligible by this
  * selector if it is present in the list of hosts of a given [[HostGroup]].
  */
class HostGroupHostSelector(store: Storage, stateStore: StateStorage,
                           executor: ExecutorService)
                           (implicit context: ExecutionContext)
        extends HostSelector {

    private val log = LoggerFactory.getLogger("org.midonet.containers-selector-hostgroup")

    private val discardedSubject = PublishSubject.create[UUID]

    private var knownHostIds = Set.empty[UUID]

    private var lastWatchedHostGroup: HostGroup = null

    private var initialSet = Promise[Set[UUID]]

    private var currentSCG: ServiceContainerGroup = null

    private var hostGroupWatcher: DeviceWatcher[HostGroup] = null

    /**
      * Observable that emits events as soon as a host is no longer eligible. In
      * the case of this `anywhere` selector, it will emit events when any host
      * is removed from ZOOM.
      */
    override val discardedHosts: Observable[UUID] = discardedSubject.asObservable()

    /**
      * This method returns a future with a [[Set]] of [[UUID]] of the eligible
      * hosts. This method will complete immediately if the set of eligible
      * hosts is already available.
      *
      * @return [[Future[Set[UUID]]]
      */
    override def candidateHosts: Future[Set[UUID]] = {
        if (initialSet == null || initialSet.isCompleted)
            Promise.successful(knownHostIds).future
        else {
            initialSet.future
        }
    }

    /**
      * Upon an update on the container group managed by this instance, this
      * method checks whether we need to change our subscription to the associated
      * host group.
      * @param scg [[ServiceContainerGroup]] that was updated
      */
    override def onServiceContainerGroupUpdate(scg: ServiceContainerGroup): Unit = {
        if (currentSCG == null || currentSCG.getHostGroupId != scg.getHostGroupId) {
            // not init or scg changed
            if (currentSCG != null)
                // scg changed, unsubscribe first
                hostGroupWatcher.unsubscribe()

            // not init, or subscription changed
            hostGroupWatcher = getHostGroupWatcher(scg.getHostGroupId)
            hostGroupWatcher.subscribe()
            currentSCG = scg
        }
        // else already init and nothing changed
    }

    private def getHostGroupWatcher(hgId: UUID): DeviceWatcher[HostGroup] = {
        new DeviceWatcher[HostGroup](
            store,
            onHostGroupUpdate,
            onHostGroupDelete,
            (hg: HostGroup) => hg.getId == toProto(hgId),
            executor
        )
    }


    private def onHostGroupDelete(id: Object): Unit = {
        id match {
            case hostId: Commons.UUID =>
                log.debug(s"Host Group ${makeReadable(hostId)} deleted.")
            case _ =>
        }
    }

    private def onHostGroupUpdate(hg: HostGroup): Unit = {
        if (lastWatchedHostGroup ne null) {
            for (id <- lastWatchedHostGroup.getHostIdsList) {
                if (!hg.getHostIdsList.contains(id))
                    discardedSubject.onNext(fromProto(id))
            }
        }
        // Update knownHostIds
        knownHostIds = hg.getHostIdsList.map(fromProto(_)).toSet
        lastWatchedHostGroup = hg
        initialSet.success(knownHostIds)
        initialSet = null
    }
}
