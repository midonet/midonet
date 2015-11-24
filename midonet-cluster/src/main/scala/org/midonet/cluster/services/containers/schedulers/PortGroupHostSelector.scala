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
import java.util.concurrent.ExecutorService
import java.util.{NoSuchElementException, UUID}

import rx.schedulers.Schedulers

import org.midonet.cluster.data.storage.{SingleValueKey, StateKey, StateStorage, Storage}
import org.midonet.cluster.models.Commons
import org.midonet.cluster.models.Topology.{PortGroup, Port, ServiceContainerGroup}
import org.midonet.cluster.services.{MidonetBackend, DeviceWatcher}
import org.midonet.cluster.util.UUIDUtil._
import org.midonet.cluster.util.logging.ProtoTextPrettifier._
import org.midonet.util.functors._
import org.slf4j.LoggerFactory
import rx.Observable
import rx.subjects.{ReplaySubject, PublishSubject}

import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.collection.concurrent.TrieMap

/**
  * This class implements the [[HostSelector]] trait. A host is eligible by this
  * selector if its associated port belongs to the list of ports in a given
  * [[PortGroup]] and it is an active port.
  */
class PortGroupHostSelector(store: Storage, stateStore: StateStorage,
                            executor: ExecutorService)
                           (implicit context:ExecutionContext)
        extends HostSelector {

    private val log = LoggerFactory.getLogger("org.midonet.containers-selector-portgroup")

    private val scheduler = Schedulers.from(executor)

    private var knownHostIds = Set.empty[UUID]

    private var initialSet = Promise[Set[UUID]]

    private var currentSCG: ServiceContainerGroup = null

    private var portGroupState: PortGroupState = _

    private val discardedSubject = PublishSubject.create[UUID]

    /**
      * Observable that emits events as soon as a host is no longer eligible. In
      * the case of this `port group host selector`, it will emit events when
      * 1) any port in the port group becomes inactive, 2) the list of ports
      * in the port group changes (only notifying the related hosts) and 3) the
      * port group associated to this container group is changed (which will
      * probably mean changing the eligible hosts).
      * is removed from ZOOM.
      */
    override val discardedHosts: Observable[UUID] = discardedSubject
        .observeOn(scheduler)

    /**
      * Current set of candidate hosts. In the case of this `anywhere` scheduler,
      * this is the whole list of hosts registered on ZOOM (either dead or alive).
      * @return [[Future]] Set[UUID]
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
      * port groups.
      * @param scg [[ServiceContainerGroup]] that was updated
      */
    override def onServiceContainerGroupUpdate(scg: ServiceContainerGroup): Unit = {
        log.debug(s"onServiceContainerGroupUpdate ${makeReadable(scg)}")
        if (currentSCG == null || currentSCG.getPortGroupId != scg.getPortGroupId) {
            // not init or scg changed
            if (currentSCG != null) {
                // scg changed, unsubscribe first if port group changed
                log.debug(s"Port group changed, unsubscribe from ${makeReadable(currentSCG.getPortGroupId)})")
                portGroupState.complete()
                // portGroupWatcher.unsubscribe()
            }

            // not init, or port group changed
            log.debug(s"Port group changed, subscribe to ${makeReadable(scg.getPortGroupId)})")
            //portGroupWatcher = getPortGroupWatcher(scg.getPortGroupId)
            //portGroupWatcher.subscribe()
            portGroupState = new PortGroupState(scg.getPortGroupId)
            portGroupState.observable
                .observeOn(scheduler)
                .subscribe(makeAction1(onPortGroupStateUpdate))
            currentSCG = scg
        }
        // else already init and nothing changed
    }

    private var currentPortGroupState: PortGroupState = null

    private def isInitialized: Boolean = currentPortGroupState == null

    private def onPortGroupStateUpdate(pgs: PortGroupState): Unit = {
        log.debug(s"onPortGroupStateUpdate ${makeReadable(pgs.pgId)}")
        if (isInitialized) {
            // initial list of hosts, complete the future
            currentPortGroupState = pgs
            knownHostIds = pgs.currentHosts
            if (initialSet != null) {
                initialSet.success(knownHostIds)
                initialSet = null
            }
        }
        else {
            log.debug(s"Updating known hosts and notifying discarded hosts")
            // notify of a host removal
            val removedHosts = knownHostIds.diff(pgs.currentHosts)
            val addedHosts = pgs.currentHosts.diff(knownHostIds)
            removedHosts foreach discardedSubject.onNext
            knownHostIds --= removedHosts
            knownHostIds ++= addedHosts
        }
    }

    private class PortGroupState(val pgId: UUID) {

        private val mark = PublishSubject.create()

        private var portStates = new TrieMap[UUID, (PortState, Port, Boolean)]()

        private var currentPortGroup: PortGroup = _

        def currentHosts: Set[UUID] = {
            var hosts = Set.empty[UUID]
            for ((_, port, state) <- portStates.values) {
                if (state)
                    hosts += port.getHostId.asJava
            }
            hosts
        }

        private def isValid(pg: PortGroup): Boolean = {
            portStates.size == pg.getPortIdsCount
        }

        private val portGroupStateSubject = ReplaySubject.create[PortGroupState]

        val observable: Observable[PortGroupState] = portGroupStateSubject.asObservable

        private val portGroupObservable = store.observable(classOf[PortGroup], pgId)
                .observeOn(scheduler)
                .subscribe(makeAction1(subscribeToPortState))
                //.subscribe(makeAction1(subscribeToPortState))

        private def subscribeToPortState(pg: PortGroup): Unit = {
            log.debug(s"Subscribing to port state changes")
            currentPortGroup = pg
            // Remove non exising ports
            portStates --= portStates.keySet.diff(pg.getPortIdsList.toSet)

            // Add new port state subscriptions
            for (portId <- pg.getPortIdsList) {
                if (!portStates.contains(portId)) {
                    val portState = new PortState(portId)
                    portState.observable
                        .observeOn(scheduler)
                        .subscribe(makeAction1(updatePortState))
                }
            }
        }

        def updatePortState(event: (PortState, Port, Boolean)): Unit = {
            log.debug(s"Port update! ${makeReadable(event._2)}")
            event match {
                case (portState, port, state) =>
                    portStates.put(port.getId, (portState, port, state))
            }
            if (isValid(currentPortGroup)) {
                log.debug(s"Emitting port group state.")
                portGroupStateSubject.onNext(this)
            }
        }

        def complete() = {
            mark.onCompleted()
            for ((portState, port, state) <- portStates.values) {
                portState.complete()
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

        var currentPort: Port = _

        var currentState: StateKey = _

        private val mark = PublishSubject.create()

        private var valid: Boolean = _

        private def setValid(value: Boolean): Unit = {
            log.debug(s"Setting the port state as valid $value")
            valid = value
        }

        def isValid(event: (PortState, Port, Boolean)): Boolean = valid

        private val portObservable = store.observable(classOf[Port], portId)
                .observeOn(scheduler)
                .doOnNext(makeAction1 { p => setValid(false) })
                .takeUntil(mark)

        private val hostIdObservable = portObservable
                .observeOn(scheduler)
                .map[String](makeFunc1(p => p.getHostId.asJava.toString))
                .distinctUntilChanged()
                .takeUntil(mark)

        private val portStateObservable = stateStore
                .keyObservable(
                    hostIdObservable, classOf[Port], portId, MidonetBackend.ActiveKey)
                .observeOn(scheduler)
                .doOnNext(makeAction1(p => setValid(true)))
                .takeUntil(mark)

        /**
          * Observable where to listen for events on port state
          * [[rx.Observable]] of ([[Port]], [[Boolean]]) specifying wether
          * the port is active or not.
          */
        val observable = Observable
                .combineLatest[Port, StateKey, (PortState, Port, Boolean)](
                    portObservable,
                    portStateObservable,
                    makeFunc2[Port, StateKey, (PortState, Port, Boolean)] {
                        case (p, s) => {
                            log.debug(s"Port state changed, ${makeReadable(p)} -> $s")
                            (this, p, s.nonEmpty)
                        }
                    }
                )
                .observeOn(scheduler)
                .filter(makeFunc1(isValid))
                .takeUntil(mark)

        /** Completes the observable corresponding to this port state */
        def complete() = mark.onCompleted()

    }

}
