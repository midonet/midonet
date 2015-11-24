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
import java.util.concurrent.ConcurrentHashMap

import org.midonet.cluster.data.storage.{StateKey, ConcurrentStateModificationException, StateStorage, Storage}
import org.midonet.cluster.models.Commons
import org.midonet.cluster.models.Topology.{Port, HostGroup, ServiceContainerGroup}
import org.midonet.cluster.services.{MidonetBackend, DeviceWatcher}
import org.midonet.cluster.util.UUIDUtil._
import org.midonet.cluster.util.logging.ProtoTextPrettifier._
import org.midonet.util.functors._
import org.slf4j.LoggerFactory
import rx.Observable
import rx.subjects.PublishSubject

import scala.concurrent.{Future, Promise}

class PortGroupHostSelector(store: Storage, stateStore: StateStorage, scg: ServiceContainerGroup) extends HostSelector {

    private val log = LoggerFactory.getLogger("org.midonet.containers.selector.portgroup")

    private val discardedSubject = PublishSubject.create[UUID]

    override val discardedHosts: Observable[UUID] = discardedSubject.asObservable()

    private var knownHostIds = Set.empty[UUID]

    private val pgToMapperMap = new ConcurrentHashMap[UUID, PortState]()

    private var lastWatchedPortGroup: HostGroup = null

    private var initialSet = Promise[Set[UUID]]

    private val portGroupWatcher = new DeviceWatcher[HostGroup](
        store,
        onPortGroupUpdate,
        onPortGroupDelete,
        (hg: HostGroup) => hg.getId == scg.getHostGroupId
    )

    def onPortGroupDelete(id: Object): Unit = {
        case protoid: Commons.UUID =>
            log.debug(s"Port Group ${makeReadable(protoid)} deleted.")
        case _ =>
    }

    def onPortGroupUpdate(hg: HostGroup): Unit = {
        if (lastWatchedPortGroup ne null) {
            for (id <- lastWatchedPortGroup.getHostIdsList) {
                if (!hg.getHostIdsList.contains(id))
                    discardedSubject.onNext(fromProto(id))
            }
        }
        // get all ids before to avoid two passes
        knownHostIds = hg.getHostIdsList.map(fromProto(_)).toSet
        lastWatchedPortGroup = hg
        initialSet.success(knownHostIds)
        initialSet = null
    }

    override def candidateHosts: Future[Set[UUID]] = {
        if (initialSet == null || initialSet.isCompleted)
            Promise.successful(knownHostIds).future
        else {
            portGroupWatcher.subscribe()
            initialSet.future
        }
    }

    /**
      * Stores the state of a port group port, and exposes and [[rx.Observable]]
      * that emits updates for this port. This observable completes either when
      * the port is deleted, or when calling the complete() methods, which is
      * used to signal that a port no longer belongs to a port group.
      */
    private class PortState(val portId: UUID) {
        private var currentPort: Port = null

        private val mark = PublishSubject.create[(UUID, Observable[StateKey])]()

        private val portObservable = store.observable(classOf[Port], portId)
                .filter(makeFunc1(hostChanged))
                .map(makeFunc1(getPortStateKey))
                .subscribe(portStateSubject)

        /** Observable where to listen for events on port state
          * return (hostId: UUID, [[rx.Observable]][StateKey])
          */
        val portStateSubject = PublishSubject.create[(UUID, Observable[StateKey])]

        /** Completes the observable corresponding to this port state */
        def complete() = mark.onCompleted()

        /** check if the host for a given port changed */
        private def hostChanged(newPort: Port): Boolean = {
            currentPort.getHostId != newPort.getHostId
        }

        /**
          *
          * @param p
          * @return
          */
        private def getPortStateKey(p: Port): (UUID, Observable[StateKey]) = {
            (p.getHostId,
             stateStore.keyObservable(p.getHostId.toString,
                                      classOf[Port],
                                      portId,
                                      MidonetBackend.ActiveKey))
        }

    }

}
