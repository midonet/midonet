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

import java.util
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

import javax.annotation.Nullable

import scala.collection.mutable

import com.typesafe.scalalogging.Logger

import rx.Observable.OnSubscribe
import rx.subscriptions.Subscriptions
import rx.{Subscription, Observer, Observable, Subscriber}
import rx.subjects.PublishSubject

import org.midonet.cluster.data.ZoomConvert
import org.midonet.midolman.logging.MidolmanLogging
import org.midonet.midolman.routingprotocols.{BGPRoute, BGP}
import org.midonet.midolman.topology.BGPMapper.{BGPPort, State, BGPState, BGPUpdate}
import org.midonet.cluster.models.Topology.{Bgp => TopologyBGP, BgpRoute => TopologyRoute}
import org.midonet.midolman.topology.devices.{RouterPort, Port}
import org.midonet.util.functors.{makeAction0, makeAction1, makeFunc1}

object BGPMapper {
    trait BGPUpdate
    case class BGPPort(port: RouterPort) extends BGPUpdate
    case class BGPAdded(bgp: BGP) extends BGPUpdate
    case class BGPUpdated(bgp: BGP) extends BGPUpdate
    case class BGPRemoved(bgpId: UUID) extends BGPUpdate
    case class BGPRouteAdded(route: BGPRoute) extends BGPUpdate
    case class BGPRouteRemoved(route: BGPRoute) extends BGPUpdate

    /** The state of the mapper subscription to the underlying storage
      * observables. Unlike the device mappers, the BGP mapper subscribes
      * itself to storage in order to expose only the BGP updates. */
    private object State extends Enumeration {
        type State = Value
        val Subscribed, Unsubscribed, Completed, Error = Value
    }

    private val EmptyUpdateArray = Array.empty[BGPUpdate]

    /**
     * Stores the state for a BGP peering, and exposes an [[rx.Observable]] that
     * emits updates for this BGP peering. The observable completes either when
     * the peering is deleted, or when calling the `complete()` method, which is
     * used to signal that a peering no longer belongs to a port.
     */
    private final class BGPState(bgpId: UUID, vt: VirtualTopology, log: Logger) {

        private var currentBgp: BGP = null

        private val routes = new mutable.HashMap[UUID, RouteState]
        private val subject = PublishSubject.create[Observable[BGPUpdate]]

        private val bgpSubscriber = new Subscriber[BGP] {
            override def onNext(bgp: BGP) = bgpUpdated(bgp)
            override def onCompleted() = bgpDeleted()
            override def onError(e: Throwable) = bgpError(e)
        }
        private val bgpSubscription = vt.store
            .observable(classOf[TopologyBGP], bgpId)
            .distinctUntilChanged()
            .map[BGP](makeFunc1(ZoomConvert.fromProto(_, classOf[BGP])))
            .observeOn(vt.scheduler)
            .subscribe(bgpSubscriber)

        /** An observable that emits BGP updates for this BGP peering state. */
        val observable = Observable.merge(subject)

        /** Completes the observable corresponding to this BGP peering state,
          * and all corresponding route states. Before completion, the state
          * will notify the removal of all current BGP routes and a
          * [[BGPRemoved]] update. */
        def complete(): Unit = {
            if (!bgpSubscription.isUnsubscribed) {
                bgpSubscription.unsubscribe()
                bgpDeleted()
            }
        }
        /** Gets the underlying BGP peering for this state. */
        @inline @Nullable def bgp: BGP = currentBgp
        /** Indicates whether the BGP state has received the BGP data. */
        @inline def isReady: Boolean = currentBgp ne null

        val removedRoutes = new mutable.HashSet[BGPRoute]

        /**
         * Sets the sequence of initialization updates for this BGP peering.
         * This is used by subsequent subscribers to receive the updates from
         * all BGP peering entries, including any advertised routes.
         * @param updates A list to which this method appends the initialization
         *                updates.
         */
        def initializationUpdates(updates: util.ArrayList[BGPUpdate]) = {
            log.debug("Initialize updates for BGP peering {} ready {}", bgpId,
                      Boolean.box(isReady))
            if (isReady) {
                updates add BGPAdded(bgp)
                for (route <- routes.values) {
                    route.initializationUpdates(updates)
                }
            }
        }

        /** Processes updates to the BGP peering, by emitting a [[BGPAdded]] or
          * [[BGPUpdated]] notification and updating any of the corresponding
          * BGP routes. */
        private def bgpUpdated(bgp: BGP): Unit = {
            vt.assertThread()
            log.debug("BGP peering updated: {}", bgp)

            // If this is the first notification, emit a BGP added update.
            if (currentBgp eq null) {
                subject onNext Observable.just(BGPAdded(bgp))
            } else {
                subject onNext Observable.just(BGPUpdated(bgp))
            }

            // Remove the state for the routes that are no longer part of this
            // BGP peering.
            for ((routeId, route) <- routes.toList
                 if !bgp.bgpRouteIds.contains(routeId)) {
                routes -= routeId
                route.complete()
            }

            // Add the state for the new routes of this BGP peering, and notify
            // its observable on the routes observable.
            for (routeId <- bgp.bgpRouteIds if !routes.contains(routeId)) {
                val routeState = new RouteState(routeId, vt, log)
                routes += routeId -> routeState
                subject onNext routeState.observable
            }

            currentBgp = bgp
        }

        /** Method called when the BGP peering is deleted either by receiving
          * an `onCompleted` notification, or when calling the `complete()`
          * method. It completes each route state for this BGP peering and
          * emits a [[BGPRemoved]] notification. */
        private def bgpDeleted(): Unit = {
            log.debug("BGP peering deleted {}", bgpId)
            for (route <- routes.values) {
                route.complete()
            }
            subject onNext Observable.just(BGPRemoved(bgpId))
            subject.onCompleted()
        }

        /** Method called when the BGP peering receives an error. It behaves
          * the same as the `bgpDeleted()` method, except that it propagates
          * the error to the output observable. */
        private def bgpError(e: Throwable): Unit = {
            log.debug("BGP peering error {}", bgpId, e)
            for (route <- routes.values) {
                route.complete()
            }
            subject onNext Observable.just(BGPRemoved(bgpId))
            subject onError e
        }
    }

    /**
     * Stores the state for a BGP route, and exposes an [[rx.Observable]] that
     * emits updates for this BGP route. The observable completes either when
     * the route is deleted, or when calling the `complete()` method, which is
     * used to signal that a route no longer belongs to a BGP peering.
     */
    private final class RouteState(routeId: UUID, vt: VirtualTopology,
                                   log: Logger) {

        private var currentRoute: BGPRoute = null

        private val routeSubject = PublishSubject.create[BGPUpdate]
        private val routeSubscription = vt.store
            .observable(classOf[TopologyRoute], routeId)
            .distinctUntilChanged()
            .map[BGPRoute](makeFunc1(ZoomConvert.fromProto(_, classOf[BGPRoute])))
            .observeOn(vt.scheduler)
            .onErrorResumeNext(Observable.empty())
            .doOnCompleted(makeAction0(routeDeleted()))
            .flatMap[BGPUpdate](makeFunc1(routeUpdated))
            .subscribe(routeSubject)

        /** Observable that emits BGP updates for this route. */
        val observable = routeSubject.asObservable

        /** Completes the observable corresponding to this route state. */
        def complete(): Unit = {
            routeSubscription.unsubscribe()
            if (currentRoute ne null) {
                routeSubject onNext BGPRouteRemoved(currentRoute)
            }
            routeSubject.onCompleted()
        }

        /** Indicates whether the route state has received the route data. */
        @inline def isReady: Boolean = currentRoute ne null

        /** Sets the initialization update for this route. */
        def initializationUpdates(updates: util.ArrayList[BGPUpdate]): Unit = {
            if (isReady) updates add BGPRouteAdded(currentRoute)
        }

        /** Called when a BGP route is updated. It generates zero or more route
          * updates when the route changes. */
        private def routeUpdated(route: BGPRoute): Observable[BGPUpdate] = {
            // Ignore the update when the route has not changed.
            if (route == currentRoute) {
                return Observable.empty()
            }
            vt.assertThread()
            log.debug("BGP route updated: {}", route)

            val updatesObservable = routesAsObservable(route, currentRoute)
            currentRoute = route
            updatesObservable
        }

        /** Called when a BGP route is deleted or its observable completes due
          * to an error. It emits a [[BGPRouteRemoved]] notification that
          * deletes the current advertised route. */
        private def routeDeleted(): Unit = {
            vt.assertThread()
            log.debug("BGP route deleted: {}", currentRoute)

            if (currentRoute ne null) {
                routeSubject onNext BGPRouteRemoved(currentRoute)
            }
        }

        @inline
        private def routesAsObservable(added: BGPRoute, removed: BGPRoute)
        : Observable[BGPUpdate] = {
            val updates: Array[BGPUpdate] =
                if ((added ne null) && (removed ne null))
                    Array(BGPRouteRemoved(removed), BGPRouteAdded(added))
                else if (added ne null)
                    Array(BGPRouteAdded(added))
                else if (removed ne null)
                    Array(BGPRouteRemoved(removed))
                else
                    EmptyUpdateArray
            Observable.from(updates)
        }
    }
}

/**
 * An implementation of the [[OnSubscribe]] interface for an [[rx.Observable]]
 * that emits [[BGPUpdate]] notifications.
 */
final class BGPMapper(portId: UUID, vt: VirtualTopology = VirtualTopology.self)
    extends OnSubscribe[BGPUpdate] with MidolmanLogging {

    override def logSource = s"org.midonet.routing.bgp.port-$portId"

    private val state = new AtomicReference(State.Unsubscribed)
    @volatile private var error: Throwable = null

    private var portSubscription: Subscription = null
    private lazy val portObserver = new Observer[RouterPort] {
        override def onNext(port: RouterPort) = portUpdated(port)
        override def onCompleted() = portDeleted()
        override def onError(e: Throwable) = portOrBgpError(e)
    }

    private val bgps = new mutable.HashMap[UUID, BGPState]
    private val subject = PublishSubject.create[Observable[BGPUpdate]]

    private lazy val errorAction = makeAction1(portOrBgpError)
    private lazy val unsubscribeAction = makeAction0(onUnsubscribe())

    private lazy val observable = Observable
        .merge(subject)
        .startWith(initializationUpdates)

    /** Processes new subscriptions to an observable created for this mapper. */
    override def call(child: Subscriber[_ >: BGPUpdate]): Unit = {
        // If the mapper is in any terminal state, complete the child
        // immediately and return.
        if (state.get == State.Completed) {
            child.onCompleted()
            return
        }
        if (state.get == State.Error) {
            child.onError(error)
            return
        }
        // Otherwise, schedule the subscription on the VT thread.
        vt.execute {
            if (state.compareAndSet(State.Unsubscribed, State.Subscribed)) {
                portSubscription = VirtualTopology
                    .observable[Port](portId)
                    .filter(makeFunc1(_.isInstanceOf[RouterPort]))
                    .map[RouterPort](makeFunc1(_.asInstanceOf[RouterPort]))
                    .subscribe(portObserver)
            }
            observable subscribe child
            child.add(Subscriptions.create(unsubscribeAction))
        }
    }

    /** Processes updates from the topology port observable. This examines the
      * addition/removal of the port BGP entries, and add/removes the
      * corresponding BGP observables. */
    private def portUpdated(port: RouterPort): Unit = {
        vt.assertThread()
        log.debug("Port updated: {}", port)

        subject onNext Observable.just(BGPPort(port))

        // Only advertised the routes for exterior ports.
        val bgpIds = if (port.isExterior) port.bgpIds else Set.empty[UUID]

        // Complete the observables for the BGP peering no longer part of this
        // bridge.
        for ((bgpId, bgpState) <- bgps.toList if !bgpIds.contains(bgpId)) {
            bgps -= bgpId
            bgpState.complete()
        }

        // Create observables for the new BGP peering for this port, and notify
        // them on the BGP subject.
        for (bgpId <- bgpIds if !bgps.contains(bgpId)) {
            val bgpState = new BGPState(bgpId, vt, log)
            bgps += bgpId -> bgpState
            subject onNext bgpState.observable.doOnError(errorAction)
        }
    }

    /** Method called when the port is deleted. The method notifies the
      * completion updates before completing the underlying observables. */
    private def portDeleted(): Unit = {
        vt.assertThread()
        log.debug("Port deleted")
        state.set(State.Completed)
        for (bgp <- bgps.values) {
            bgp.complete()
        }
        bgps.clear()
        subject.onCompleted()
    }

    /** Method called when the port or a BGP state observable emits an error.
      * The method notifies the completion updates before completing the
      * underlying observables. */
    private def portOrBgpError(e: Throwable): Unit = {
        vt.assertThread()
        log.error("Port/BGP/BGP route error", e)
        state.set(State.Error)
        error = e
        for (bgp <- bgps.values) {
            bgp.complete()
        }
        bgps.clear()
        subject.onError(e)
    }

    /** A handler called when a subscriber unsubscribes. If there are no more
      * subscribers, the method unsubscribes from the underlying observbales,
      * and clears any internal state. */
    private def onUnsubscribe(): Unit = vt.execute {
        if (!subject.hasObservers &&
            state.compareAndSet(State.Subscribed, State.Unsubscribed)) {
            log.debug("Unsubscribing from port notifications")

            for (bgpState <- bgps.values) {
                bgpState.complete()
            }
            bgps.clear()
            portSubscription.unsubscribe()
            portSubscription = null
        }
    }

    /** Returns an observable that emits a set of BGP updates corresponding to
      * the initial state of the router port. This is only used when the mapper
      * is used by more than one subscriber, and any non-initial subscriber
      * may encounter the mapper in a non-initial state. */
    private def initializationUpdates: util.List[BGPUpdate] = {
        vt.assertThread()
        val updates = new util.ArrayList[BGPUpdate](2 * bgps.size)
        for (bgp <- bgps.values) {
            bgp.initializationUpdates(updates)
        }
        log.debug("Initialization updates for subscriber: {}", updates)
        updates
    }
}
