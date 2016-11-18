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

package org.midonet.midolman.topology

import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

import com.google.common.annotations.VisibleForTesting

import rx.Observable.OnSubscribe
import rx.{Observable, Subscriber, Subscription}
import rx.subjects.{BehaviorSubject, PublishSubject}
import rx.subscriptions.Subscriptions

import org.midonet.midolman.logging.MidolmanLogging
import org.midonet.midolman.simulation.{Port, RouterPort}
import org.midonet.midolman.topology.BgpPortMapper.RouterState
import org.midonet.midolman.topology.DeviceMapper.MapperState
import org.midonet.midolman.topology.VirtualTopology.Key
import org.midonet.midolman.topology.devices._
import org.midonet.packets.IPv4Addr
import org.midonet.quagga.BgpdConfiguration.{BgpRouter => BgpConfig}
import org.midonet.util.functors._
import org.midonet.util.logging.Logger

object BgpPortMapper {

    /**
     * Stores the state for a BGP router, and exposes an [[Observable]] that
     * emits updates when the BGP router (i.e. the router AS number, BGP
     * networks and BGP peers) changes. The observable completes either when
     * the router is deleted, or when calling the `complete()` method, which is
     * used to signal that the router is no longer the device of the current
     * port.
     */
    private final class RouterState(portId: UUID, routerId: UUID,
                                    vt: VirtualTopology, log: Logger) {

        private var currentRouter: BgpRouter = null
        private val mark = PublishSubject.create[BgpRouter]

        val observable = VirtualTopology
            .observable(classOf[BgpRouter], routerId)
            .doOnNext(makeAction1(currentRouter = _))
            .doOnCompleted(makeAction0(routerCompleted()))
            .takeUntil(mark)

        /** Gets the current BGP router. */
        def router = currentRouter
        /** Completes the observable corresponding to this router state. */
        def complete(): Unit = mark.onCompleted()
        /** Indicates whether the router state has received the BGP router
          * data. */
        def isReady: Boolean = currentRouter ne null

        /** Handles the completion of the BGP router observable. Under normal
          * circumstances this observable should never complete by its own,
          * as long as the port belongs to the router. This is used to indicate
          * an error in notification stream for the BGP port, which should
          * terminate the updates for the BGP port. */
        private def routerCompleted(): Unit = {
            log.warn("BGP router {} unexpectedly completed notifications",
                     routerId)
            throw BgpRouterDeleted(portId, routerId)
        }
    }
}

/**
 * An implementation of the [[OnSubscribe]] interface for an [[rx.Observable]]
 * that emits [[devices.BgpPort]] updates.
 */
final class BgpPortMapper(portId: UUID, vt: VirtualTopology)
    extends OnSubscribe[devices.BgpPort] with MidolmanLogging {

    override def logSource = "org.midonet.routing.bgp"
    override def logMark = s"port:$portId"

    private val key = Key(classOf[devices.BgpPort], portId)
    private val state = new AtomicReference(MapperState.Unsubscribed)

    private lazy val unsubscribeAction = makeAction0(onUnsubscribe())

    @volatile private var routerPort: RouterPort = null
    @volatile private var routerState: RouterState = null
    @volatile private var error: Throwable = null

    private val routerSubject = PublishSubject.create[Observable[BgpRouter]]
    private lazy val routerObservable = Observable
        .merge(routerSubject)
        .map[RouterPort](makeFunc1(routerUpdated))
        .doOnError(makeAction1(routerError))
        .onErrorResumeNext(Observable.empty)

    private lazy val portObservable = VirtualTopology
        .observable(classOf[Port], portId)
        .flatMap[RouterPort](makeFunc1(portUpdated))
        .doOnCompleted(makeAction0(portDeleted()))
        .doOnError(makeAction1(portError))
        .onErrorResumeNext(Observable.empty)

    private lazy val bgpObservable: Observable[devices.BgpPort] =
        Observable.merge(routerObservable,
                         portObservable)
                  .filter(makeFunc1(isBgpReady))
                  .map(makeFunc1(buildBgp))

    private val bgpSubject = BehaviorSubject.create[devices.BgpPort]
    @volatile private var bgpSubscription: Subscription = null

    /** Processes new subscriptions to an observable created for this mapper. */
    override def call(child: Subscriber[_ >: devices.BgpPort]): Unit = {
        // If the mapper is in any terminal state, complete the child
        // immediately and return.
        if (state.get == MapperState.Completed) {
            child.onCompleted()
            return
        }
        if (state.get == MapperState.Error) {
            child onError error
            return
        }
        if (state.get == MapperState.Closed) {
            child onError DeviceMapper.MapperClosedException
            return
        }
        // Otherwise, schedule the subscription on the VT thread.
        vt.executeVt {
            if (state.compareAndSet(MapperState.Unsubscribed,
                                    MapperState.Subscribed)) {
                bgpSubscription = bgpObservable subscribe bgpSubject
            }

            bgpSubject subscribe child
            child add Subscriptions.create(unsubscribeAction)
        }
    }

    /** Returns the current mapper state. */
    @VisibleForTesting
    protected[topology] def mapperState = state.get

    /** Returns whether the mapper has any subscribed observers. */
    @VisibleForTesting
    protected[topology] def hasObservers = bgpSubject.hasObservers

    /** Processes updates for the port, by validating whether the port is a
      * router port and throwing an exception otherwise. */
    private def portUpdated(port: Port): Observable[RouterPort] = {
        vt.assertThread()
        log.debug("Port updated: {}", port)

        // Validate the port as a router port: otherwise complete
        port match {
            case routerPort: RouterPort => routerPortUpdated(routerPort)
            case _ if !state.get.isTerminal =>
                onCompleted()
                Observable.empty()
            case _ => Observable.empty() // Ignore a terminal state
        }
    }

    /** Processes updates for the router port, by checking whether the port is
      * an exterior port, subscribing to updates from the corresponding BGP
      * router, and emitting a corresponding BGP port notification. */
    private def routerPortUpdated(port: RouterPort): Observable[RouterPort] = {
        // If this is the first port, or of the port router has changed, create
        // a new BGP router state and emit its observable on the router subject.
        if ((routerPort eq null) || routerPort.routerId != port.routerId){
            // Complete any previous BGP router state.
            if (routerState ne null) {
                routerState.complete()
            }

            routerState = new RouterState(portId, port.routerId, vt, log)
            routerSubject onNext routerState.observable
        }

        // Set the current router port.
        routerPort = port
        Observable.just(routerPort)
    }

    /** Handles the deletion of the BGP port, by emitting an error that contains
      * the port identifier. */
    private def portDeleted(): Unit = {
        // Ignore notifications if the mapper is in a terminal state.
        if (state.get.isTerminal)
            return
        onError(BgpPortDeleted(portId))
    }

    /** Handles errors for the BGP port. */
    private def portError(e: Throwable): Unit = {
        // Ignore notifications if the mapper is in a terminal state.
        if (state.get.isTerminal) return

        log.debug("Port error", e)
        onError(BgpPortError(portId, e))
    }

    /** Handles updates for the BGP router. */
    private def routerUpdated(router: BgpRouter): RouterPort = {
        vt.assertThread()
        log.debug("Router updated: {}", router)
        // Return the router port to use the observable in merge.
        routerPort
    }

    /** Handles errors for the BGP router. */
    private def routerError(e: Throwable): Unit = {
        // Ignore notifications if the mapper is in a terminal state.
        if (state.get.isTerminal) return

        val wrappedException = e match {
            case BgpRouterDeleted(_,_) => e
            case _ => BgpPortError(portId, e)
        }
        log.debug("Router error", wrappedException)
        onError(wrappedException)
    }

    /** A handler called when a subscriber unsubscribes. If there are no more
      * subscribers, the method unsubscribes from the underlying observables,
      * and clears any internal state. The mapper is set in an error state, such
      * that any subsequent subscriber will ne notified immediately that the
      * mapper is no longer available. */
    private def onUnsubscribe(): Unit = {
        // Ignore notifications if the mapper is in a terminal state.
        if (state.get.isTerminal) return

        val subscription = bgpSubscription
        if (!bgpSubject.hasObservers && (subscription ne null) &&
            state.compareAndSet(MapperState.Subscribed, MapperState.Closed)) {
            log.debug("Closing the BGP port notification stream")

            subscription.unsubscribe()
            bgpSubscription = null
        }
    }

    /** Completes the BGP port observable, when the emitted port is not a
      * router port. */
    private def onCompleted(): Unit = {
        // Change the mapper state
        state set MapperState.Completed

        bgpSubject.onCompleted()
        routerSubject.onCompleted()

        vt.observables.remove(key)

        // Unsubscribe from the underlying observables.
        val subscription = bgpSubscription
        if (subscription ne null) {
            subscription.unsubscribe()
            bgpSubscription = null
        }

        // Complete all the router state and subject.
        val routerState = this.routerState
        if (routerState ne null) {
            routerState.complete()
            this.routerState = null
        }
    }

    /** Handles all errors that can be emitted by the mapper observable. The
      * method sets the current state to error, sets the error that will be
      * emitted to any new subscriber, emits the error on the BGP subject to
      * any current subscriber, and unsubscribes from the underlying storage
      * observables. */
    private def onError(e: Throwable): Unit = {
        // Change the mapper state and emit the error on the output observable.
        error = e
        state set MapperState.Error

        bgpSubject onError e
        routerSubject.onCompleted()

        vt.observables.remove(key)

        // Unsubscribe from the underlying observables.
        val subscription = bgpSubscription
        if (subscription ne null) {
            subscription.unsubscribe()
            bgpSubscription = null
        }

        // Complete all the router state and subject.
        val routerState = this.routerState
        if (routerState ne null) {
            routerState.complete()
            this.routerState = null
        }
    }

    /** Filters BGP notifications, until both the router port and the
      * corresponding BGP router become available. */
    private def isBgpReady(port: RouterPort): Boolean = {
        vt.assertThread()
        val ready = (routerPort ne null) && (routerState ne null) &&
                    routerState.isReady && !state.get.isTerminal
        log.debug("BGP port ready: {}", Boolean.box(ready))
        ready
    }

    /** Builds the BGP port notification from the current router port and BGP
      * router. */
    private def buildBgp(port: RouterPort): devices.BgpPort = {
        vt.assertThread()
        log.debug("Building BGP for port: {} and BGP router: {}", port,
                  routerState.router)

        // Filter the neighbors based on the port IP address
        val neighbors =
            if (port.containerId != null) routerState.router.neighbors
            else if (port.portAddress4 ne null)
                routerState.router.neighbors
                           .filterKeys(port.portAddress4.containsAddress)
            else Map.empty[IPv4Addr, BgpNeighbor]

        val portAddress =
            if (port.portAddress4 ne null) port.portAddress4.getAddress
            else IPv4Addr.AnyAddress
        BgpPort(port, BgpConfig(as = routerState.router.as,
                                id = portAddress,
                                neighbors = neighbors.mapValues(_.neighbor),
                                networks = routerState.router.networks),
                neighbors.values.map(_.id).toSet)
    }

}
