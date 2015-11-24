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

import java.util.{NoSuchElementException, UUID}

import org.midonet.cluster.data.storage.{StateKey, StateStorage, Storage}
import org.midonet.cluster.models.Commons
import org.midonet.cluster.models.Topology.{PortGroup, Port, ServiceContainerGroup}
import org.midonet.cluster.services.{MidonetBackend, DeviceWatcher}
import org.midonet.cluster.util.UUIDUtil._
import org.midonet.cluster.util.logging.ProtoTextPrettifier._
import org.midonet.util.functors._
import org.slf4j.LoggerFactory
import rx.Observable
import rx.subjects.PublishSubject

import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.collection.concurrent.TrieMap

/**
  * This class implements the [[HostSelector]] trait. A host is eligible by this
  * selector if its associated port belongs to the list of ports in a given
  * [[PortGroup]] and it is an active port.
  */
class PortGroupHostSelector(store: Storage, stateStore: StateStorage)
                           (implicit context:ExecutionContext)
        extends HostSelector {

    private val log = LoggerFactory.getLogger("org.midonet.containers.selector.portgroup")

    private val discardedSubject = PublishSubject.create[UUID]

    override val discardedHosts: Observable[UUID] = discardedSubject.asObservable()

    private var knownHostIds = Set.empty[UUID]

    private val pgToMapperMap = new TrieMap[UUID, PortState]()

    private var lastWatchedPortGroup: PortGroup = null

    private var initialSet = Promise[Set[UUID]]

    private var currentSCG: ServiceContainerGroup = null

    private var portGroupWatcher: DeviceWatcher[PortGroup] = null

    override def candidateHosts: Future[Set[UUID]] = {
        if (initialSet == null || initialSet.isCompleted)
            Promise.successful(knownHostIds).future
        else {
            portGroupWatcher.subscribe()
            initialSet.future
        }
    }

    override def onServiceContainerGroupUpdate(scg: ServiceContainerGroup): Unit = {
        if (currentSCG == null || currentSCG.getPortGroupId != scg.getPortGroupId) {
            // not init or scg changed
            if (currentSCG != null) {
                // scg changed, unsubscribe first
                log.debug(s"Port group changed, unsubscribe from ${makeReadable(currentSCG.getPortGroupId)})")
                portGroupWatcher.unsubscribe()
            }

            // not init, or subscription changed
            log.debug(s"Port group changed, subscribe to ${makeReadable(scg.getPortGroupId)})")
            portGroupWatcher = getPortGroupWatcher(scg.getPortGroupId)
            portGroupWatcher.subscribe()
            currentSCG = scg
        }
        // else already init and nothing changed
    }

    private def getPortGroupWatcher(pgId: UUID): DeviceWatcher[PortGroup] = {
        new DeviceWatcher[PortGroup](
            store,
            onPortGroupUpdate,
            onPortGroupDelete,
            (pg: PortGroup) => pg.getId == toProto(pgId)
        )
    }

    private def onPortGroupUpdate(pg: PortGroup): Unit = {
        log.debug(s"onPortGroupUpdate ${makeReadable(pg.getId)}")
        if (lastWatchedPortGroup != null
                && !lastWatchedPortGroup.getPortIdsList.equals(pg.getPortIdsList)) {
            // the port group member list has changed
            for (pid <- lastWatchedPortGroup.getPortIdsList) {
                log.debug(s"Port group member ports changed.")
                if (!pg.getPortIdsList.contains(pid)) {
                    // Unsubscribe from port state watcher
                    pgToMapperMap.get(pid).get.complete()
                    pgToMapperMap.remove(pid)
                    discardedSubject.onNext(fromProto(pid))
                }
            }
        }
        for (pid <- pg.getPortIdsList) {
            if (!pgToMapperMap.contains(pid)) {
                val portState = new PortState(pid)
                pgToMapperMap.put(pid, portState)
                // on every port update...
                portState.observable.map[Unit](makeFunc1(onPortStateUpdate))
            }
        }
        lastWatchedPortGroup = pg
    }

    private def onPortGroupDelete(id: Object): Unit = {
        id match {
            case protoid: Commons.UUID =>
                log.debug(s"onPortGroupDelete ${makeReadable(protoid)} deleted.")
            case _ =>
        }
    }

    private def onPortStateUpdate(event: (Port, Boolean)): Unit = event match {
        case (port, false) =>
            // port is inactive, remove from knownHostIds
            knownHostIds -= port.getHostId
            checkInitialSet()
        case (port, true) =>
            // port became active, add it to the knownHostIds
            knownHostIds += port.getHostId
            checkInitialSet()
    }

    /**
      * This method checks that the initial set of hosts is available, meaning
      * that the ports in the port group already reported its state.
      */
    private def checkInitialSet(): Unit = {
        if (initialSet != null) {
            // Get the first element, if exists
            val numInitialized = pgToMapperMap.values.map { ps =>
                try {
                    ps.observable.first().toBlocking.single()
                } catch {
                    case nse: NoSuchElementException => (null, false)
                }
            }.count { case (port, active) => port != null }

            if (numInitialized == pgToMapperMap.keys.size) {
                initialSet.success(knownHostIds)
                initialSet = null
            }
        }
    }


    /**
      * Stores the state of a port group port, and exposes and [[rx.Observable]]
      * that emits updates for this port. This observable completes either when
      * the port is deleted, or when calling the complete() methods, which is
      * used to signal that a port no longer belongs to a port group. The
      * exposed observable combines the latest updates from both the observed
      * port as well as its state.
      */
    private class PortState(val portId: UUID) {
        private var currentPort: Port = null

        private val mark = PublishSubject.create()

        private var valid: Boolean = _

        private def setValid(value: Boolean): Unit = {
            valid = value
        }

        private def isValid(event: (Port, Boolean)): Boolean = valid

        private val portObservable = store.observable(classOf[Port], portId)
                .doOnNext(makeAction1 { p => setValid(false) })
                .takeUntil(mark)

        private val hostIdObservable = portObservable
                .map[String](makeFunc1(p => p.getHostId.toString))
                .distinctUntilChanged()
                .takeUntil(mark)

        private val portStateObservable = stateStore
                .keyObservable(
                    hostIdObservable, classOf[Port], portId, MidonetBackend.ActiveKey)
                .doOnNext(makeAction1 { p => setValid(true) } )
                .takeUntil(mark)
        /**
          * Observable where to listen for events on port state
          * [[rx.Observable]] of ([[Port]], [[Boolean]]) specifying wether
          * the port is active or not.
          */
        val observable = Observable
                .combineLatest[Port, StateKey, (Port, Boolean)](
                    portObservable,
                    portStateObservable,
                    makeFunc2[Port, StateKey, (Port, Boolean)] {
                        case (p:Port, s:StateKey) => (p, s.nonEmpty)
                    }
                )
                .filter(makeFunc1(isValid))
                .takeUntil(mark)

        /** Completes the observable corresponding to this port state */
        def complete() = mark.onCompleted()

        /** check if the host for a given port changed */
        private def hostChanged(updatedPort: Port): Boolean = {
            if (currentPort != null) {
                val result = currentPort.getHostId != updatedPort.getHostId
                currentPort = updatedPort
                result
            }
            else {
                currentPort = updatedPort
                true
            }
        }
    }

}
