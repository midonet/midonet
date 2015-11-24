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
import java.util.concurrent.ConcurrentHashMap

import com.google.inject.Inject
import org.midonet.cluster.data.storage.{StateKey, StateStorage, Storage}
import org.midonet.cluster.models.Commons
import org.midonet.cluster.models.Topology.{Host, ServiceContainerGroup}
import org.midonet.cluster.models.State.HostState
import org.midonet.cluster.services.{DeviceWatcher, MidonetBackend}
import org.midonet.cluster.util.UUIDUtil.fromProto
import org.midonet.util.concurrent.toFutureOps
import org.midonet.cluster.util.UUIDUtil._
import org.slf4j.LoggerFactory
import rx.Observable
import rx.subjects.PublishSubject
import scala.collection.JavaConversions._
import scala.concurrent.{Promise, Future}
import scala.util.Success
import scala.util.control.NonFatal

class AnywhereHostSelector (store: Storage, stateStore: StateStorage, scg: ServiceContainerGroup) extends HostSelector {

    private val log = LoggerFactory.getLogger("org.midonet.containers.selector.anywhere")

    private val discardedSubject = PublishSubject.create[UUID]

    override val discardedHosts: Observable[UUID] = discardedSubject.asObservable()

    private var isReady = false

    private var knownHostIds = Set.empty[UUID]
    private var deletedWhileInitializing = Set.empty[UUID]

    override def candidateHosts: Future[Set[UUID]] = {
        if (isReady)
            Promise.successful(knownHostIds).future
        else {
            hostWatcher.subscribe()
            val allHosts = store.getAll(classOf[Host])
                    .recover{
                        case NonFatal(t) => List.empty[Host]
                    }
                    .map(_.map(h => fromProto(h.getId))
                            .filterNot(deletedWhileInitializing.contains).toSet)
            isReady = true
            deletedWhileInitializing = Set.empty[UUID]
            allHosts
        }
    }


    private val hostWatcher = new DeviceWatcher[Host](
        store,
        onHostUpdate,
        onHostDelete)

    private def onHostUpdate(host: Host): Unit = {
        knownHostIds += fromProto(host.getId)
    }

    private def onHostDelete(id: Object): Unit = id match {
        case id: Commons.UUID =>
            val pid = fromProto(id)
            knownHostIds -= pid
            if (!isReady) {
                deletedWhileInitializing += pid
            }
            discardedSubject.onNext(pid)
        case _ =>
    }
}
