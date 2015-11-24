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
import org.midonet.cluster.data.storage.{StateStorage, Storage}
import org.midonet.cluster.models.Commons
import org.midonet.cluster.models.Topology.{ServiceContainerGroup, Host}
import org.midonet.cluster.services.DeviceWatcher
import org.midonet.cluster.util.UUIDUtil._
import org.slf4j.LoggerFactory
import rx.Observable
import rx.subjects.PublishSubject

import scala.concurrent.{Future, Promise}
import scala.util.control.NonFatal

/**
  * Singleton object for the anywhere selector, as it does not depend on the
  * specific service container group being scheduled. Any container group
  * using the anywhere allocation policy will use this instance.
  */
object AnywhereHostSelector extends HostSelector {
    @Inject
    val stateStore: StateStorage = null

    @Inject
    val store: Storage = null

    val selector = new AnywhereHostSelector(store, stateStore)

    override val discardedHosts: Observable[UUID] = selector.discardedHosts

    override val candidateHosts: Future[Set[UUID]] = selector.candidateHosts

    override def onServiceContainerGroupUpdate(scg: ServiceContainerGroup): Unit = {
        selector.onServiceContainerGroupUpdate(scg)
    }
}

/**
  * This class implements the [[HostSelector]] trait. A host is eligible by this
  * selector if the host is registered in zoom. It basically returns the list
  * of all hosts available.
  */
class AnywhereHostSelector (store: Storage, stateStore: StateStorage)
        extends HostSelector {

    private val log = LoggerFactory.getLogger("org.midonet.containers.selector.anywhere")

    private var knownHostIds = Set.empty[UUID]

    private var deletedWhileInitializing = Set.empty[UUID]

    private val hostWatcher = new DeviceWatcher[Host](
        store,
        onHostUpdate,
        onHostDelete)

    private val discardedSubject = PublishSubject.create[UUID]

    override val discardedHosts: Observable[UUID] = discardedSubject.asObservable()

    override def candidateHosts: Future[Set[UUID]] = {
        if (deletedWhileInitializing == null)
            Promise.successful(knownHostIds).future
        else {
            val allHosts = store.getAll(classOf[Host])
                    .recover{
                        case NonFatal(t) => List.empty[Host]
                    }
                    .map(_.map(h => fromProto(h.getId))
                            .filterNot(deletedWhileInitializing.contains).toSet)
            deletedWhileInitializing = null
            allHosts
        }
    }

    override def onServiceContainerGroupUpdate(scg: ServiceContainerGroup): Unit = {
        hostWatcher.subscribe()
    }

    private def onHostUpdate(host: Host): Unit = {
        knownHostIds += fromProto(host.getId)
    }

    private def onHostDelete(id: Object): Unit = id match {
        case id: Commons.UUID =>
            val pid = fromProto(id)
            knownHostIds -= pid
            if (deletedWhileInitializing != null) {
                deletedWhileInitializing += pid
            }
            discardedSubject.onNext(pid)
        case _ =>
    }
}
