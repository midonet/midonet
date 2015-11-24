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

import org.midonet.cluster.data.storage.{StateStorage, Storage}
import org.midonet.cluster.models.Commons
import org.midonet.cluster.models.Topology.{HostGroup, Host, ServiceContainerGroup}
import org.midonet.cluster.services.DeviceWatcher
import org.midonet.cluster.util.UUIDUtil._
import org.midonet.cluster.util.logging.ProtoTextPrettifier
import org.midonet.cluster.util.logging.ProtoTextPrettifier._
import org.midonet.util.concurrent.toFutureOps
import org.midonet.util.functors._
import org.slf4j.LoggerFactory
import rx.Observable
import rx.subjects.PublishSubject

import scala.concurrent.{Future, Promise}
import scala.util.control.NonFatal

class HostGroupHostSelector(store: Storage, stateStore: StateStorage, scg: ServiceContainerGroup) extends HostSelector {

    private val log = LoggerFactory.getLogger("org.midonet.containers.selector.hostgroup")

    private val discardedSubject = PublishSubject.create[UUID]

    override val discardedHosts: Observable[UUID] = discardedSubject.asObservable()

    private var knownHostIds = Set.empty[UUID]

    private var lastWatchedHostGroup: HostGroup = null

    private var initialSet = Promise[Set[UUID]]

    private val hostGroupWatcher = new DeviceWatcher[HostGroup](
        store,
        onHostGroupUpdate,
        onHostGroupDelete,
        (hg: HostGroup) => hg.getId == scg.getHostGroupId
    )

    def onHostGroupDelete(id: Object): Unit = {
        case protoid: Commons.UUID =>
            log.debug(s"Host Group ${makeReadable(protoid)} deleted.")
        case _ =>
    }

    def onHostGroupUpdate(hg: HostGroup): Unit = {
        if (lastWatchedHostGroup ne null) {
            for (id <- lastWatchedHostGroup.getHostIdsList) {
                if (!hg.getHostIdsList.contains(id))
                    discardedSubject.onNext(fromProto(id))
            }
        }
        // get all ids before to avoid two passes
        knownHostIds = hg.getHostIdsList.map(fromProto(_)).toSet
        lastWatchedHostGroup = hg
        initialSet.success(knownHostIds)
        initialSet = null
    }

    override def candidateHosts: Future[Set[UUID]] = {
        if (initialSet == null || initialSet.isCompleted)
            Promise.successful(knownHostIds).future
        else {
            hostGroupWatcher.subscribe()
            initialSet.future
        }
    }
}
